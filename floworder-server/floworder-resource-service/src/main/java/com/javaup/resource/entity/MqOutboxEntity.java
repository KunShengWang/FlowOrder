package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fo_mq_outbox")
public class MqOutboxEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String messageId;
    private String producerService;
    private String bizKey;
    private String messageType;
    private String exchangeName;
    private String routingKey;
    private String content;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private LocalDateTime claimUntil;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}