package com.javaup.mq.service;


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
    boolean claim(Long id);

    void markSent(Long id);

    void markFailed(Long id, Integer currentRetryCount, String error);

    int reclaimExpiredClaims();
}
