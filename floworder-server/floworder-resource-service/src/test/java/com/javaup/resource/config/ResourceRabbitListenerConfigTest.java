package com.javaup.resource.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceRabbitListenerConfigTest {

    @Test
    void resultConsumerExecutorShouldGrowToMaxWithoutQueuingLongLivedConsumers()
            throws InterruptedException {
        ResourceRabbitListenerConfig config = new ResourceRabbitListenerConfig(true);
        ThreadPoolTaskExecutor executor = config.orderResultConsumerExecutor(2, 4, 0);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(4, executor.getActiveCount());
            assertEquals(4, executor.getPoolSize());
            assertEquals(0, executor.getQueueSize());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
