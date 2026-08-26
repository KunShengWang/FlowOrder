package com.javaup.resource.mq.metrics;

import com.javaup.mq.OutboxPublishResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxPublishMetrics {

    private static final String PRODUCER = "resource-service";

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter rejected;
    private final Counter expiredClaims;
    private final Counter staleWorkers;
    private final Timer claimToWorkerStart;
    private final Timer confirmLatency;

    public OutboxPublishMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = registry.counter("floworder.outbox.claim", "producer", PRODUCER);
        this.rejected = registry.counter("floworder.outbox.executor.rejected", "producer", PRODUCER);
        this.expiredClaims = registry.counter("floworder.outbox.claim.expired", "producer", PRODUCER);
        this.staleWorkers = registry.counter("floworder.outbox.worker.stale", "producer", PRODUCER);
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
