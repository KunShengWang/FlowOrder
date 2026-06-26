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

    @Bean(name = "stockCompensationTaskScheduler", destroyMethod = "shutdown")// Spring 容器关闭的时候，会自动调用这个 Bean 的 shutdown() 方法
    public ThreadPoolTaskScheduler stockCompensationTaskScheduler(
            @Value("${floworder.thread-pool.stock-compensation.size:1}") int poolSize) {
        return buildScheduler("stock-compensation-", poolSize);
    }

    @Bean(name = "deadLetterTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler deadLetterTaskScheduler(
            @Value("${floworder.thread-pool.dead-letter-monitor.size:1}")
            int poolSize) {
        return buildScheduler("dead-letter-monitor-", poolSize);
    }

    @Bean(name = "v8RequestTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler v8RequestTaskScheduler(
            @Value("${floworder.thread-pool.v8-dispatch.size:1}")
            int poolSize
    ) {
        return buildScheduler("v8-request-dispatch-", poolSize);
    }

    private ThreadPoolTaskScheduler buildScheduler(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);// 1个调度线程
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);// 停机时等正在跑的任务结束
        scheduler.setAwaitTerminationSeconds(10);// 最多等10秒
        scheduler.setErrorHandler(throwable ->
                log.error("定时任务线程池执行异常, threadNamePrefix={}", threadNamePrefix, throwable)
        );// 任务抛异常时兜底，不打爆线程
        return scheduler;
    }
}