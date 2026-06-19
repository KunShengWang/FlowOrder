package com.javaup.resource.mq.service;

import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.dto.OrderStateChangedMessage;

import java.time.LocalDateTime;
import java.util.List;

public interface MqDeadLetterService {

    /**
     * Persist a RabbitMQ dead letter and isolate an uncertain create command.
     */
    void record(String deadQueue,
                String messageId,
                String content,
                String deathReason);

    List<MqDeadLetterAdminDto> find(Integer status, int limit);

    MqDeadLetterAdminDto findById(Long id);

    /**
     * 人工重放
     */
    void replay(Long id, String operator);

    void resolveOrderResult(OrderCreateResultMessage message);

    void resolveOrderState(OrderStateChangedMessage message);

    void ignore(Long id, String operator, String reason, boolean force);

    void recoverStaleReplaying(LocalDateTime deadline, int limit);

    long countUnresolved();
}
