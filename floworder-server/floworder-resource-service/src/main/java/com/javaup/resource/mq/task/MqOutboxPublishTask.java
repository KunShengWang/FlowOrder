package com.javaup.resource.mq.task;

import com.javaup.mq.OutboxPublishResult;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.metrics.OutboxPublishMetrics;
import com.javaup.resource.mq.publisher.OutboxMessagePublisher;
import com.javaup.resource.mq.service.MqOutboxService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "floworder.mq.outbox-publish-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class MqOutboxPublishTask {

    private final MqOutboxService mqOutboxService;
    private final OutboxMessagePublisher publisher;
    private final ThreadPoolTaskExecutor publisherExecutor;
    private final OutboxPublishMetrics metrics;
    private final int batchSize;
    private final long leaseSeconds;
    private final long localBackpressureDelayMillis;
    private final long localBackpressureJitterMillis;
    private final long idleBackoffMillis;
    private final Semaphore inFlightPermits;
    private final String claimOwner;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private long nextLeaseReclaimNanos;

    public MqOutboxPublishTask(
            MqOutboxService mqOutboxService,
            OutboxMessagePublisher publisher,
            @Qualifier("resourceOutboxPublisherExecutor") ThreadPoolTaskExecutor publisherExecutor,
            OutboxPublishMetrics metrics,
            @Value("${floworder.mq.outbox.batch-size:20}") int batchSize,
            @Value("${floworder.mq.outbox.lease-seconds:60}") long leaseSeconds,
            @Value("${floworder.mq.outbox.local-backpressure-delay-ms:250}") long localBackpressureDelayMillis,
            @Value("${floworder.mq.outbox.local-backpressure-jitter-ms:250}") long localBackpressureJitterMillis,
            @Value("${floworder.mq.outbox.scan-delay-ms:200}") long idleBackoffMillis,
            @Value("${floworder.mq.outbox.publisher.max-in-flight:6}") int maxInFlight,
            @Value("${spring.application.name:floworder-resource-service}") String applicationName
    ) {
        this.mqOutboxService = mqOutboxService;
        this.publisher = publisher;
        this.publisherExecutor = publisherExecutor;
        this.metrics = metrics;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.localBackpressureDelayMillis = Math.max(1, localBackpressureDelayMillis);
        this.localBackpressureJitterMillis = Math.max(0, localBackpressureJitterMillis);
        this.idleBackoffMillis = Math.max(1, idleBackoffMillis);
        this.inFlightPermits = new Semaphore(Math.max(1, maxInFlight));
        this.claimOwner = applicationName + ":" + UUID.randomUUID();
        this.metrics.bindPublisherExecutor(publisherExecutor, inFlightPermits::availablePermits);
    }

    @Scheduled(
            fixedDelayString = "${floworder.mq.outbox.scan-delay-ms:200}",
            scheduler = "resourceOutboxTaskScheduler"
    )
    public void publish() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            reclaimExpiredClaimsIfDue();

            int queryLimit = reservePermits();
            if (queryLimit == 0) {
                return;
            }

            List<MqOutboxEntity> records;
            try {
                records = mqOutboxService.findSendable(queryLimit);
            } catch (RuntimeException exception) {
                inFlightPermits.release(queryLimit);
                throw exception;
            }
            inFlightPermits.release(queryLimit - records.size());
            if (records.isEmpty()) {
                metrics.emptyScan();
                return;
            }

            for (MqOutboxEntity record : records) {
                dispatch(record);
            }
        }
    }

    private void reclaimExpiredClaimsIfDue() {
        long now = System.nanoTime();
        if (now < nextLeaseReclaimNanos) {
            return;
        }
        nextLeaseReclaimNanos = now + TimeUnit.MILLISECONDS.toNanos(idleBackoffMillis);
        int reclaimed = mqOutboxService.reclaimExpiredClaims(batchSize);
        if (reclaimed > 0) {
            metrics.expiredClaims(reclaimed);
        }
    }

    private int reservePermits() {
        try {
            while (running.get()) {
                if (!inFlightPermits.tryAcquire(1, idleBackoffMillis, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                if (!running.get()) {
                    inFlightPermits.release();
                    return 0;
                }
                int additional = Math.min(batchSize - 1, inFlightPermits.availablePermits());
                if (additional > 0 && !inFlightPermits.tryAcquire(additional)) {
                    inFlightPermits.release();
                    continue;
                }
                return additional + 1;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private void dispatch(MqOutboxEntity record) {
        String claimToken = mqOutboxService.claim(record.getId(), claimOwner, leaseSeconds);
        if (claimToken == null) {
            inFlightPermits.release();
            return;
        }
        metrics.claimed();
        long claimedNanos = System.nanoTime();
        try {
            publisherExecutor.execute(() -> publishClaimed(record, claimToken, claimedNanos));
            metrics.dispatched();
        } catch (RuntimeException exception) {
            metrics.rejected();
            long delayMillis = localBackpressureDelayMillis + jitter(localBackpressureJitterMillis);
            try {
                if (!mqOutboxService.releaseClaim(
                        record.getId(), claimToken, delayMillis, "本机Outbox发布执行器已满")) {
                    metrics.staleWorker();
                }
            } catch (RuntimeException releaseException) {
                log.error("本机拒绝后释放Outbox claim失败, id={}, messageId={}",
                        record.getId(), record.getMessageId(), releaseException);
            } finally {
                inFlightPermits.release();
            }
            log.warn("Outbox发布任务被本机执行器拒绝, id={}, messageId={}, delayMs={}",
                    record.getId(), record.getMessageId(), delayMillis);
        }
    }

    @PreDestroy
    void stopDispatching() {
        running.set(false);
    }

    private void publishClaimed(MqOutboxEntity record, String claimToken, long claimedNanos) {
        metrics.claimToWorkerStart(System.nanoTime() - claimedNanos);
        try {
            OutboxPublishResult result = publisher.publish(record, claimToken);
            metrics.publishResult(result);
            boolean updated = result.successful()
                    ? mqOutboxService.markSent(record.getId(), claimToken)
                    : mqOutboxService.markFailed(
                            record.getId(), claimToken, record.getRetryCount(), result.error());
            if (!updated) {
                metrics.staleWorker();
                log.warn("Outbox旧worker终态更新被fencing, id={}, messageId={}, outcome={}",
                        record.getId(), record.getMessageId(), result.outcome());
            }
        } catch (RuntimeException exception) {
            log.error("Outbox投递worker异常, id={}, messageId={}",
                    record.getId(), record.getMessageId(), exception);
        } finally {
            inFlightPermits.release();
        }
    }

    private long jitter(long bound) {
        return bound == 0 ? 0 : ThreadLocalRandom.current().nextLong(bound + 1);
    }
}
