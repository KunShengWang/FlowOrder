package com.javaup.mq.service;


import com.javaup.dto.MqOutboxAdminDto;
import com.javaup.entity.MqOutboxEntity;

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

    boolean markSent(Long id, String claimToken);

    boolean markFailed(Long id, String claimToken, Integer currentRetryCount, String error);

    boolean releaseClaim(Long id, String claimToken, long delayMillis, String error);

    int reclaimExpiredClaims(int limit);

    List<MqOutboxAdminDto> findDead(int limit);

    void retryDead(String messageId);

    void replaySent(String messageId);

    void replayConsumerDead(String messageId);
}
