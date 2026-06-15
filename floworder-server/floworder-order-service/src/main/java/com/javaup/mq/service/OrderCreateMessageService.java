package com.javaup.mq.service;

import com.javaup.dto.OrderCreateMessage;

public interface OrderCreateMessageService {

    void consume(OrderCreateMessage command);

    void recordFailure(OrderCreateMessage message, String reason);
}
