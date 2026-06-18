package com.javaup.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@Configuration
public class OrderTaskSchedulerConfig {

    @Bean(name = "orderOutboxTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler orderOutboxTaskScheduler(
            @Value("${floworder.thread-pool.order-outbox.size:2}") int poolSize) {
        return buildScheduler("order-outbox-", poolSize);
    }

    @Bean(name = "orderTimeoutTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler orderTimeoutTaskScheduler(
            @Value("${floworder.thread-pool.order-timeout.size:1}") int poolSize) {
        return buildScheduler("order-timeout-", poolSize);
    }

    private ThreadPoolTaskScheduler buildScheduler(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(throwable ->
                log.error("定时任务线程池执行异常, threadNamePrefix={}", threadNamePrefix, throwable)
        );
        return scheduler;
    }
}