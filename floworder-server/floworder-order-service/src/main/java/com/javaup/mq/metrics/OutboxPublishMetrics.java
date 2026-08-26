package com.javaup.mq.metrics;

import com.javaup.mq.OutboxPublishResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.function.IntSupplier;

@Component
public class OutboxPublishMetrics {

    private static final String PRODUCER = "order-service";

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter rejected;
    private final Counter expiredClaims;
    private final Counter staleWorkers;
    private final Counter dispatched;
    private final Counter emptyScans;
    private final Timer claimToWorkerStart;
    private final Timer confirmLatency;
    private IntSupplier availablePermits;

    public OutboxPublishMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = registry.counter("floworder.outbox.claim", "producer", PRODUCER);
        this.rejected = registry.counter("floworder.outbox.executor.rejected", "producer", PRODUCER);
        this.expiredClaims = registry.counter("floworder.outbox.claim.expired", "producer", PRODUCER);
        this.staleWorkers = registry.counter("floworder.outbox.worker.stale", "producer", PRODUCER);
        this.dispatched = registry.counter("floworder.outbox.scanner.dispatch", "producer", PRODUCER);
        this.emptyScans = registry.counter("floworder.outbox.scanner.empty", "producer", PRODUCER);
        this.claimToWorkerStart = Timer.builder("floworder.outbox.claim.to.worker.start")
                .description("claim_to_worker_start_ms")
                .tag("producer", PRODUCER)
                .publishPercentileHistogram()
                .register(registry);
        this.confirmLatency = Timer.builder("floworder.outbox.confirm.latency")
                .tag("producer", PRODUCER)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void claimed() {
        claimed.increment();
    }

    public void rejected() {
        rejected.increment();
    }

    public void expiredClaims(int count) {
        expiredClaims.increment(count);
    }

    public void staleWorker() {
        staleWorkers.increment();
    }

    public void dispatched() {
        dispatched.increment();
    }

    public void emptyScan() {
        emptyScans.increment();
    }

    public void bindPublisherExecutor(ThreadPoolTaskExecutor executor, IntSupplier availablePermits) {
        this.availablePermits = availablePermits;
        Gauge.builder("floworder.outbox.publisher.worker.active", executor, ThreadPoolTaskExecutor::getActiveCount)
                .tag("producer", PRODUCER)
                .register(registry);
        Gauge.builder("floworder.outbox.publisher.executor.queue.size", executor, ThreadPoolTaskExecutor::getQueueSize)
                .tag("producer", PRODUCER)
                .register(registry);
        Gauge.builder("floworder.outbox.publisher.permits.available", this.availablePermits, IntSupplier::getAsInt)
                .tag("producer", PRODUCER)
                .register(registry);
    }

    public void claimToWorkerStart(long nanos) {
        claimToWorkerStart.record(Duration.ofNanos(Math.max(0, nanos)));
    }

    public void publishResult(OutboxPublishResult result) {
        confirmLatency.record(Duration.ofMillis(Math.max(0, result.confirmLatencyMillis())));
        registry.counter(
                "floworder.outbox.publish.result",
                "producer", PRODUCER,
                "outcome", result.outcome().name()
        ).increment();
    }
}
