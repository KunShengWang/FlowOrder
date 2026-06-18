package com.javaup.resource.mq.service;

import com.javaup.dto.OrderStateChangedMessage;

public interface OrderStateMessageService {

    Long handle(OrderStateChangedMessage message);
}
