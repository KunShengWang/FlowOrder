package com.javaup.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// TODO 这块不懂，需要讲解下
@Configuration
public class OrderRabbitListenerConfig {

    @Bean
    public ThreadPoolTaskExecutor orderCreateConsumerExecutor(
            @Value("${floworder.thread-pool.order-create-consumer.core-size:2}") int coreSize,
            @Value("${floworder.thread-pool.order-create-consumer.max-size:4}") int maxSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("order-create-consumer-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory orderCreateListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("orderCreateConsumerExecutor")
            ThreadPoolTaskExecutor orderCreateConsumerExecutor,
            @Value("${floworder.rabbit.order-create.concurrent-consumers:2}") int concurrentConsumers,
            @Value("${floworder.rabbit.order-create.max-concurrent-consumers:4}") int maxConcurrentConsumers,
            @Value("${floworder.rabbit.order-create.prefetch:10}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setTaskExecutor(orderCreateConsumerExecutor);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(concurrentConsumers);
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);
        factory.setPrefetchCount(prefetch);
        return factory;
    }
}
