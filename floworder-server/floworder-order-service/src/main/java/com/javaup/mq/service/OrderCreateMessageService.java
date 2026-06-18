package com.javaup.mq.service;

import com.javaup.dto.OrderCreateMessage;

public interface OrderCreateMessageService {

    /**
     * rabbitmq消费消息常见订单
     */
    void consume(OrderCreateMessage command);

    /**
     * 记录失败消息
     */
    void recordFailure(OrderCreateMessage command, String error);
}
