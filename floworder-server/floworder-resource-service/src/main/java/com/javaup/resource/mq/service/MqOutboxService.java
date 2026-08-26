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
    String claim(Long id, String claimOwner, long leaseSeconds);

    /**
     * 消息发送到rabbitmq成功
     */
    boolean markSent(Long id, String claimToken);

    /**
     * 标记消息发送失败
     */
    boolean markFailed(Long id, String claimToken, Integer currentRetryCount, String error);

    /** 本机发布执行器拒绝后的短暂背压释放，不增加发送失败次数。 */
    boolean releaseClaim(Long id, String claimToken, long delayMillis, String error);

    /**
     * MQ Outbox 消息发送租约回收
     */
    int reclaimExpiredClaims(int limit);

    List<MqOutboxAdminDto> findDead(int limit);

    void retryDead(String messageId);

    void replaySent(String messageId);

    void replayConsumerDead(String messageId);
}
