package com.javaup.resource.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class V8ReservationExecutorConfig {

    @Bean
    public ThreadPoolExecutor v8ReservationExecutor(
            @Value("${floworder.v8.worker.core-size:4}") int coreSize,
            @Value("${floworder.v8.worker.max-size:8}") int maxSize,
            @Value("${floworder.v8.worker.queue-capacity:100}") int queueCapacity
    ) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "v8-reservation-worker-"
                            + sequence.incrementAndGet()
            );
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}