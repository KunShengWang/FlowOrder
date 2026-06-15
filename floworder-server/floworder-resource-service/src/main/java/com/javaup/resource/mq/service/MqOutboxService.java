package com.javaup.resource.mq.service;

import com.javaup.dto.MqOutboxAdminDto;
import com.javaup.resource.entity.MqOutboxEntity;

import java.util.List;

public interface MqOutboxService {

    /**
     * 查询可发生的消息
     */
    List<MqOutboxEntity> findSendable(int limit);

    /**
     * 抢占消息
     */
    boolean claim(Long id);

    /**
     * 消息发送到rabbitmq成功
     */
    void markSent(Long id);

    /**
     * 标记消息发送失败
     */
    void markFailed(Long id, Integer currentRetryCount, String error);

    /**
     * MQ Outbox 消息发送租约回收
     */
    void reclaimExpiredClaims();

    List<MqOutboxAdminDto> findDead(int limit);

    void retryDead(String messageId);

    void replaySent(String messageId);
}
