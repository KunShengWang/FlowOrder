package com.javaup.resource.mq.service;

import com.javaup.dto.OrderStateChangedMessage;

public interface OrderStateMessageService {

    /**
     * 取消或超时时返回需要删除缓存的 stockItemId。
     */
    Long handle(OrderStateChangedMessage message);
}