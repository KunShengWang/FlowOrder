package com.javaup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fo_mq_consume_log")
public class MqConsumeLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String messageId;
    private String consumerGroup;
    private String messageType;
    private String bizKey;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}