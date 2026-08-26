package com.javaup.resource.mq.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OrderResultListenerMetrics {

    private final MeterRegistry registry;
    private final Counter consumed;
    private final Counter committed;
    private final Timer transactionLatency;
    private final AtomicInteger configuredConsumers = new AtomicInteger();
    private final AtomicInteger maxConfiguredConsumers = new AtomicInteger();

    public OrderResultListenerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.consumed = registry.counter("floworder.rabbit.order.result.consume");
        this.committed = registry.counter("floworder.rabbit.order.result.commit");
        this.transactionLatency = Timer.builder("floworder.rabbit.order.result.transaction")
                .description("Order result database transaction latency")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void consumed() {
        consumed.increment();
    }

    public void committed(long elapsedNanos) {
        committed.increment();
        transactionLatency.record(Duration.ofNanos(Math.max(0, elapsedNanos)));
    }

    public void bindExecutor(
            ThreadPoolTaskExecutor executor,
            int concurrentConsumers,
            int maxConcurrentConsumers
    ) {
        configuredConsumers.set(concurrentConsumers);
        maxConfiguredConsumers.set(maxConcurrentConsumers);
        Gauge.builder("floworder.rabbit.order.result.executor.active", executor,
                        ThreadPoolTaskExecutor::getActiveCount)
                .register(registry);
        Gauge.builder("floworder.rabbit.order.result.executor.pool.size", executor,
                        ThreadPoolTaskExecutor::getPoolSize)
                .register(registry);
        Gauge.builder("floworder.rabbit.order.result.executor.queue.size", executor,
                        ThreadPoolTaskExecutor::getQueueSize)
                .register(registry);
        Gauge.builder("floworder.rabbit.order.result.consumers.configured",
                        configuredConsumers, AtomicInteger::get)
                .register(registry);
        Gauge.builder("floworder.rabbit.order.result.consumers.max",
                        maxConfiguredConsumers, AtomicInteger::get)
                .register(registry);
    }
}
