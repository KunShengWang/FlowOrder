package com.javaup.resource.mq.service;

import com.javaup.resource.entity.MqOutboxEntity;

import java.util.List;

public interface MqOutboxService {

    /**
     * MQ Outbox 消息发送租约回收
     */
    void reclaimExpiredClaims();

    /**
     * 找到可发送的消息
     */
    List<MqOutboxEntity> findSendable(int limit);

    /**
     * 多实例间抢占消息
     */
    boolean claim(Long id);

    void markFailed(Long id, Integer retryCount, String message);

    void markSent(Long id);
}
