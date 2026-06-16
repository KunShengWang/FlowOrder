package com.javaup.resource.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ResourceRabbitListenerConfig {

    @Bean
    public ThreadPoolTaskExecutor orderResultConsumerExecutor(
            @Value("${floworder.thread-pool.order-result-consumer.core-size:2}") int coreSize,
            @Value("${floworder.thread-pool.order-result-consumer.max-size:4}") int maxSize) {
        return buildExecutor("order-result-consumer-", coreSize, maxSize);
    }

    @Bean
    public ThreadPoolTaskExecutor orderStateConsumerExecutor(
            @Value("${floworder.thread-pool.order-state-consumer.core-size:1}") int coreSize,
            @Value("${floworder.thread-pool.order-state-consumer.max-size:2}") int maxSize) {
        return buildExecutor("order-state-consumer-", coreSize, maxSize);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory orderResultListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("orderResultConsumerExecutor")
            ThreadPoolTaskExecutor orderResultConsumerExecutor,
            @Value("${floworder.rabbit.order-result.concurrent-consumers:2}") int concurrentConsumers,
            @Value("${floworder.rabbit.order-result.max-concurrent-consumers:4}") int maxConcurrentConsumers,
            @Value("${floworder.rabbit.order-result.prefetch:10}") int prefetch) {
        return buildFactory(
                connectionFactory,
                orderResultConsumerExecutor,
                concurrentConsumers,
                maxConcurrentConsumers,
                prefetch
        );
    }

    @Bean
    public SimpleRabbitListenerContainerFactory orderStateListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("orderStateConsumerExecutor")
            ThreadPoolTaskExecutor orderStateConsumerExecutor,
            @Value("${floworder.rabbit.order-state.concurrent-consumers:1}") int concurrentConsumers,
            @Value("${floworder.rabbit.order-state.max-concurrent-consumers:2}") int maxConcurrentConsumers,
            @Value("${floworder.rabbit.order-state.prefetch:5}") int prefetch) {
        return buildFactory(
                connectionFactory,
                orderStateConsumerExecutor,
                concurrentConsumers,
                maxConcurrentConsumers,
                prefetch
        );
    }

    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix, int coreSize, int maxSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    private SimpleRabbitListenerContainerFactory buildFactory(
            ConnectionFactory connectionFactory,
            ThreadPoolTaskExecutor executor,
            int concurrentConsumers,
            int maxConcurrentConsumers,
            int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setTaskExecutor(executor);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(concurrentConsumers);
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);
        factory.setPrefetchCount(prefetch);
        return factory;
    }
}