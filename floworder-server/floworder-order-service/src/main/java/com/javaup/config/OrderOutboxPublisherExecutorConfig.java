package com.javaup.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class OrderOutboxPublisherExecutorConfig {

    @Bean(name = "orderOutboxPublisherExecutor")
    public ThreadPoolTaskExecutor orderOutboxPublisherExecutor(
            @Value("${floworder.mq.outbox.publisher.workers:2}") int workers,
            @Value("${floworder.mq.outbox.publisher.queue-capacity:4}") int queueCapacity,
            @Value("${floworder.mq.outbox.publisher.max-in-flight:6}") int maxInFlight,
            @Value("${floworder.mq.outbox.publisher.shutdown-await-seconds:10}") int shutdownAwaitSeconds
    ) {
        int boundedWorkers = Math.max(1, workers);
        int boundedQueueCapacity = Math.max(1, queueCapacity);
        if (maxInFlight < 1 || maxInFlight > boundedWorkers + boundedQueueCapacity) {
            throw new IllegalArgumentException("Outbox max-in-flight必须位于1到workers+queue-capacity之间");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("order-outbox-publisher-");
        executor.setCorePoolSize(boundedWorkers);
        executor.setMaxPoolSize(boundedWorkers);
        executor.setQueueCapacity(boundedQueueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1, shutdownAwaitSeconds));
        executor.initialize();
        return executor;
    }
}
