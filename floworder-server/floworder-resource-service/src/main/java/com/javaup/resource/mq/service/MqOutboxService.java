package com.javaup.resource.mq.service;

import com.javaup.resource.entity.MqOutboxEntity;

import java.util.List;

public interface MqOutboxService {

    /**
     * MQ Outbox 消息发送租约回收
     */
    void reclaimExpiredClaims();

    /**
     * 查询可发送的消息
     */
    List<MqOutboxEntity> findSendable(int i);

    /**
     * 抢占消息
     */
    boolean claims(Long id);

    /**
     * 标记消息发送失败
     */
    void markFailed(Long id,Integer retryCount,String error);

    /**
     * 标记发送成功
     */
    void markSent(Long id);
}
