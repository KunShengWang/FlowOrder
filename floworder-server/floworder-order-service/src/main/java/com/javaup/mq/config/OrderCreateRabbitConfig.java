package com.javaup.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.constant.OrderMqConstant.ORDER_CREATE_DEAD_KEY;

@Configuration
public class OrderCreateRabbitConfig {

    /**
     * 订单交换机
     */
    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_CREATE_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 订单创建队列
     */
    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_CREATE_DEAD_KEY)
                .build();
    }

    /**
     * 订单交换机与订单创建队列绑定
     */
    @Bean
    public Binding orderCreateBinding() {
        return BindingBuilder.bind(orderCreateQueue())
                .to(orderExchange())
                .with(ORDER_CREATE_ROUTING_KEY);
    }

    /**
     * 订单死信交换机
     */
    @Bean
    public DirectExchange orderDeadLetterExchange() {
        return ExchangeBuilder.directExchange(ORDER_DLX).durable(true).build();
    }

    /**
     * 订单创建死信队列
     */
    @Bean
    public Queue orderCreateDeadQueue() {
        return QueueBuilder.durable(ORDER_CREATE_DLQ).build();
    }

    /**
     * 订单死信交换机与订单创建死信队列绑定
     */
    @Bean
    public Binding orderCreateDeadBinding() {
        return BindingBuilder.bind(orderCreateDeadQueue())
                .to(orderDeadLetterExchange())
                .with(ORDER_CREATE_DEAD_KEY);
    }

    /**
     * 订单创建结果交换机
     */
    @Bean
    public DirectExchange orderResultExchange() {
        return ExchangeBuilder.directExchange(ORDER_RESULT_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 订单状态交换机
     */
    @Bean
    public DirectExchange orderStateExchange() {
        return ExchangeBuilder.directExchange(ORDER_STATE_EXCHANGE)
                .durable(true)
                .build();
    }
}
