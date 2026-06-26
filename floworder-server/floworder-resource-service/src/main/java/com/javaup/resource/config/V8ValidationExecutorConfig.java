package com.javaup.resource.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class V8ValidationExecutorConfig {

    @Bean(
            name = "v8ValidationExecutor",
            destroyMethod = "shutdown"
    )
    public ThreadPoolTaskExecutor v8ValidationExecutor(
            @Value("${floworder.thread-pool.v8-validation.core-size:2}")
            int coreSize,
            @Value("${floworder.thread-pool.v8-validation.max-size:4}")
            int maxSize,
            @Value("${floworder.thread-pool.v8-validation.queue-capacity:100}")
            int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        executor.setThreadNamePrefix(
                "v8-validation-"
        );
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);

        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );

        executor.setTaskDecorator(mdcTaskDecorator());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();

        return executor;
    }

    private TaskDecorator mdcTaskDecorator() {
        return task -> {
            Map<String, String> captured =
                    MDC.getCopyOfContextMap();

            return () -> {
                Map<String, String> previous =
                        MDC.getCopyOfContextMap();

                try {
                    if (captured == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(captured);
                    }

                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}