package com.javaup.resource.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.javaup.constant.OrderMqConstant.*;

@Configuration
public class OrderStateRabbitConfig {

    /**
     * 订单状态交换机
     */
    @Bean
    public DirectExchange orderStateExchange() {
        return ExchangeBuilder.directExchange(ORDER_STATE_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 订单状态队列
     */
    @Bean
    public Queue orderStateQueue() {
        return QueueBuilder.durable(ORDER_STATE_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_STATE_DEAD_KEY)
                .build();
    }

    @Bean
    public Binding orderStateBinding() {
        return BindingBuilder.bind(orderStateQueue())
                .to(orderStateExchange())
                .with(ORDER_STATE_ROUTING_KEY);
    }

    /**
     * 订单状态死信队列
     */
    @Bean
    public Queue orderStateDeadQueue() {
        return QueueBuilder.durable(ORDER_STATE_DLQ).build();
    }

    @Bean
    public Binding orderStateDeadBinding(@Qualifier("orderDeadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(orderStateDeadQueue())
                .to(dlx)
                .with(ORDER_STATE_DEAD_KEY);
    }
}