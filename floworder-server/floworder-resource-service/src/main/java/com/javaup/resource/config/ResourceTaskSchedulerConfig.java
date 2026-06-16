package com.javaup.resource.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@Configuration
public class ResourceTaskSchedulerConfig {

    @Bean(name = "resourceOutboxTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler resourceOutboxTaskScheduler(
            @Value("${floworder.thread-pool.resource-outbox.size:2}") int poolSize) {
        return buildScheduler("resource-outbox-", poolSize);
    }

    @Bean(name = "stockCompensationTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler stockCompensationTaskScheduler(
            @Value("${floworder.thread-pool.stock-compensation.size:1}") int poolSize) {
        return buildScheduler("stock-compensation-", poolSize);
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