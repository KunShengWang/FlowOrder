package com.javaup.resource.task;

import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.service.ReservationRequestProcessor;
import com.javaup.resource.service.ReservationRequestService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "floworder.v8.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationRequestDispatchTask {

    private final ReservationRequestService requestService;
    private final ReservationRequestProcessor processor;
    private final ThreadPoolExecutor executor;

    private final int batchSize;
    private final int leaseSeconds;

    private final String owner = "resource-" + UUID.randomUUID();

    private final int maxRetry;

    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ReservationRequestDispatchTask(
            ReservationRequestService requestService,
            ReservationRequestProcessor processor,
            @Qualifier("v8ReservationExecutor")
            ThreadPoolExecutor executor,
            @Value("${floworder.v8.batch-size:50}")
            int batchSize,
            @Value("${floworder.v8.lease-seconds:30}")
            int leaseSeconds,
            @Value("${floworder.v8.max-retry:3}")
            int maxRetry
    ) {
        this.requestService = requestService;
        this.processor = processor;
        this.executor = executor;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxRetry = maxRetry;
    }

    @Scheduled(
            fixedDelayString = "${floworder.v8.scan-delay-ms:200}",
            scheduler = "v8RequestTaskScheduler"
    )
    public void dispatch() {
        if (!accepting.get()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        int recovered = requestService.recoverExpired(
                now, batchSize, maxRetry
        );
        if (recovered > 0) {
            log.warn("V8回收过期处理租约, recovered={}", recovered);
        }

        List<ReservationRequestEntity> candidates =
                requestService.findClaimable(now, batchSize);

        for (ReservationRequestEntity request : candidates) {
            if (!accepting.get()) {
                break;
            }
            dispatchOne(request);
        }
    }

    private void dispatchOne(ReservationRequestEntity request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimUntil = now.plusSeconds(leaseSeconds);
        boolean claimed = requestService.claim(request.getId(), owner, now, claimUntil);
        if (!claimed) {
            return;
        }
        if (!accepting.get()) {
            requestService.releaseClaim(
                    request.getId(),
                    owner,
                    LocalDateTime.now(),
                    "resource service is shutting down"
            );
            return;
        }
        log.debug(
                "V8预约请求抢占成功, requestId={}, requestDbId={}, owner={}",
                request.getRequestId(),
                request.getId(),
                owner
        );
        try {
            executor.execute(
                    () -> processor.process(request, owner)
            );
        } catch (RejectedExecutionException exception) {
            /*
             * 已抢占但未进入线程池，必须恢复成RETRY，
             * 否则会一直停留在PROCESSING直到租约超时。
             */
            requestService.releaseClaim(
                    request.getId(),
                    owner,
                    LocalDateTime.now().plusSeconds(1),
                    "v8 worker queue is full"
            );
            log.warn(
                    "V8工作队列已满, 释放请求租约, requestId={}, active={}, queueSize={}",
                    request.getRequestId(),
                    executor.getActiveCount(),
                    executor.getQueue().size()
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        log.info(
                "V8预约线程池开始关闭, active={}, queueSize={}",
                executor.getActiveCount(),
                executor.getQueue().size()
        );
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("V8预约线程池等待超时，执行强制关闭");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("V8预约线程池关闭完成");
    }
}