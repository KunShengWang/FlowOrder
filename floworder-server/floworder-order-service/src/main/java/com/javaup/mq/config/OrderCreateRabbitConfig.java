package com.javaup.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.javaup.constant.OrderMqConstant.*;

@Configuration
public class OrderCreateRabbitConfig {

    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_CREATE_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_CREATE_DEAD_KEY)
                .build();
    }

    @Bean
    public Binding orderCreateBinding() {
        return BindingBuilder.bind(orderCreateQueue())
                .to(orderExchange())
                .with(ORDER_CREATE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderDeadLetterExchange() {
        return ExchangeBuilder.directExchange(ORDER_DLX).durable(true).build();
    }

    @Bean
    public Queue orderCreateDeadQueue() {
        return QueueBuilder.durable(ORDER_CREATE_DLQ).build();
    }

    @Bean
    public Binding orderCreateDeadBinding() {
        return BindingBuilder.bind(orderCreateDeadQueue())
                .to(orderDeadLetterExchange())
                .with(ORDER_CREATE_DEAD_KEY);
    }

    @Bean
    public DirectExchange orderResultExchange() {
        return ExchangeBuilder.directExchange(ORDER_RESULT_EXCHANGE)
                .durable(true)
                .build();
    }
}
