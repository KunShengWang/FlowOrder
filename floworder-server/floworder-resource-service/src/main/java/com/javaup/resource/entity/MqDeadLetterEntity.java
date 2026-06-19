package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RabbitMQ consumer dead-letter record.
 *
 * <p>The complete original payload is retained so the message can be audited
 * and replayed after the underlying failure is repaired.</p>
 */
@Data
@TableName("fo_mq_dead_letter")
public class MqDeadLetterEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Message identifier used for producer and consumer idempotency. */
    private String messageId;

    /** RabbitMQ dead-letter queue that delivered this record. */
    private String deadQueue;

    /** Service that owns the original Outbox record. */
    private String producerService;

    private String messageType;

    /** Business identifier, currently the stock deduction number. */
    private String bizKey;

    /** Original exchange used when the message is replayed. */
    private String exchangeName;

    /** Original routing key used when the message is replayed. */
    private String routingKey;

    /** Original JSON body. Malformed bodies are also retained verbatim. */
    private String content;

    /** RabbitMQ x-death information. */
    private String deathReason;

    /** 0 pending, 10 replaying, 20 resolved, 30 ignored. */
    private Integer status;

    private Integer replayCount;

    /** Parsing errors or business-isolation anomalies. */
    private String lastError;

    private LocalDateTime replayedAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 处理人 */
    private String handledBy;

    /** 处理说明 */
    private String resolutionNote;
}
