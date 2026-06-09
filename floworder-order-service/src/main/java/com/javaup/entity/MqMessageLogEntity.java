package com.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ消息日志表实体 (fo_mq_message_log)
 */
@Data
@TableName("fo_mq_message_log")
public class MqMessageLogEntity {

    /** 主键ID */
    private Long id;

    /** 消息ID */
    private String messageId;

    /** 业务键，如orderNo/deductNo */
    private String bizKey;

    /** 消息类型 */
    private String messageType;

    /** MQ topic */
    private String topic;

    /** 消息体JSON */
    private String content;

    /** 状态：0初始化 10已发送 20已消费 30失败 40重试中 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    /** 最后一次错误 */
    private String lastError;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}