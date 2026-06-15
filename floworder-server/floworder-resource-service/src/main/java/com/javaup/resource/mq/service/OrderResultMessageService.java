package com.javaup.resource.mq.service;

import com.javaup.dto.OrderCreateResultMessage;

public interface OrderResultMessageService {

    /**
     * 失败结果返回需要删除缓存的stockItemId，成功返回null
     */
    Long handle(OrderCreateResultMessage message);
}
