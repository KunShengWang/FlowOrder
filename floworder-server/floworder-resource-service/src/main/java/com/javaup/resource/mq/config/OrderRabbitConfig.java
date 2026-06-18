package com.javaup.resource.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.javaup.constant.OrderMqConstant.*;

@Configuration
public class OrderRabbitConfig {

    /**
     * 订单创建交换机
     */
    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_CREATE_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 订单创建队列
     * 给 ORDER_CREATE_QUEUE 这个正常业务队列配置死信转发规则。当这条队列里的消息变成“死信”时，RabbitMQ 会把消息转发到 ORDER_DLX
     */
    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_CREATE_DEAD_KEY)
                .build();
    }

    /**
     * 订单创建交换机与订单创建队列之间的绑定
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
     * 订单死信交换机与订单创建死信队列之间的绑定
     */
    @Bean
    public Binding orderCreateDeadBinding() {
        return BindingBuilder.bind(orderCreateDeadQueue())
                .to(orderDeadLetterExchange())
                .with(ORDER_CREATE_DEAD_KEY);
    }
}
