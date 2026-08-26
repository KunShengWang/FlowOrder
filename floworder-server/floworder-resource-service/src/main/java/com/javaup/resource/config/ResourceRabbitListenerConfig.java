package com.javaup.resource.config;

import com.javaup.resource.mq.metrics.OrderResultListenerMetrics;
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

    private final boolean autoStartup;

    public ResourceRabbitListenerConfig(
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
            boolean autoStartup
    ) {
        this.autoStartup = autoStartup;
    }

    @Bean
    public ThreadPoolTaskExecutor orderResultConsumerExecutor(
            @Value("${floworder.thread-pool.order-result-consumer.core-size:2}") int coreSize,
            @Value("${floworder.thread-pool.order-result-consumer.max-size:4}") int maxSize,
            @Value("${floworder.thread-pool.order-result-consumer.queue-capacity:0}") int queueCapacity) {
        return buildExecutor("order-result-consumer-", coreSize, maxSize, queueCapacity);
    }

    @Bean
    public ThreadPoolTaskExecutor orderStateConsumerExecutor(
            @Value("${floworder.thread-pool.order-state-consumer.core-size:1}") int coreSize,
            @Value("${floworder.thread-pool.order-state-consumer.max-size:2}") int maxSize,
            @Value("${floworder.thread-pool.order-state-consumer.queue-capacity:0}") int queueCapacity) {
        return buildExecutor("order-state-consumer-", coreSize, maxSize, queueCapacity);
    }

    @Bean
    public ThreadPoolTaskExecutor deadLetterConsumerExecutor() {
        return buildExecutor("dead-letter-consumer-", 1, 1, 0);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory orderResultListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("orderResultConsumerExecutor")
            ThreadPoolTaskExecutor orderResultConsumerExecutor,
            OrderResultListenerMetrics listenerMetrics,
            @Value("${floworder.rabbit.order-result.concurrent-consumers:2}") int concurrentConsumers,
            @Value("${floworder.rabbit.order-result.max-concurrent-consumers:4}") int maxConcurrentConsumers,
            @Value("${floworder.rabbit.order-result.prefetch:10}") int prefetch) {
        listenerMetrics.bindExecutor(
                orderResultConsumerExecutor,
                concurrentConsumers,
                maxConcurrentConsumers
        );
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

    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("deadLetterConsumerExecutor")
            ThreadPoolTaskExecutor deadLetterConsumerExecutor) {
        return buildFactory(
                connectionFactory,
                deadLetterConsumerExecutor,
                1,
                1,
                1
        );
    }

    private ThreadPoolTaskExecutor buildExecutor(
            String threadNamePrefix,
            int coreSize,
            int maxSize,
            int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        /*
         * Rabbit consumer是长生命周期任务。这里不能使用普通任务型线程池的排队策略：
         * core线程被基础consumer永久占用后，扩容consumer会进入队列且永远等不到线程，
         * ThreadPoolExecutor也不会继续扩到maxPoolSize，最终触发60秒启动超时。
         */
        executor.setQueueCapacity(queueCapacity);
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
        factory.setConnectionFactory(connectionFactory);// RabbitMQ 连接
        factory.setTaskExecutor(executor);// 线程池
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);// 手动确认
        factory.setDefaultRequeueRejected(false);// 拒绝后不放回队列
        factory.setConcurrentConsumers(concurrentConsumers);// 并发消费者数
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);// 最大消费者数
        factory.setPrefetchCount(prefetch);// 每次预取消息数
        factory.setAutoStartup(autoStartup);// 测试或维护窗口可统一关闭消费者
        return factory;
    }
}
