package com.javaup.resource.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.javaup.constant.OrderMqConstant.*;

@Configuration
public class OrderResultRabbitConfig {

    /**
     * 订单创建结果交换机
     */
    @Bean
    public DirectExchange orderResultExchange() {
        return ExchangeBuilder.directExchange(ORDER_RESULT_EXCHANGE)
                .durable(true).build();
    }

    /**
     * 订单创建结果队列
     */
    @Bean
    public Queue orderResultQueue() {
        return QueueBuilder.durable(ORDER_RESULT_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_RESULT_DEAD_KEY)
                .build();
    }

    /**
     * 订单创建结果死信队列
     */
    @Bean
    public Queue orderResultDeadQueue() {
        return QueueBuilder.durable(ORDER_RESULT_DLQ).build();
    }

    /**
     * 订单创建结果队列与订单创建结果交换机绑定
     */
    @Bean
    public Binding orderResultBinding() {
        return BindingBuilder.bind(orderResultQueue())
                .to(orderResultExchange())
                .with(ORDER_RESULT_ROUTING_KEY);
    }

    /**
     * 订单创建结果死信队列与订单死信交换机绑定
     */
    @Bean
    public Binding orderResultDeadBinding(@Qualifier("orderDeadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(orderResultDeadQueue())
                .to(dlx).with(ORDER_RESULT_DEAD_KEY);
    }
}