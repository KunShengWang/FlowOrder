package com.javaup.resource.mq.service;

import com.javaup.dto.OrderCreateResultMessage;

public interface OrderResultMessageService {

    Long handle(OrderCreateResultMessage message);
}
