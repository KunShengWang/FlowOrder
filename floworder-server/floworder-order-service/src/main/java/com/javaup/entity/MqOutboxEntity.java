package com.javaup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ 事务 Outbox 记录。
 *
 * <p>业务数据和待发送消息在同一个本地事务中写入数据库，再由后台任务投递到 RabbitMQ，
 * 用于避免业务事务提交成功但消息发送失败导致的消息丢失。</p>
 */
@Data
@TableName("fo_mq_outbox")
public class MqOutboxEntity {

    /**
     * 主键，使用 MyBatis-Plus 雪花算法生成。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 消息唯一 ID，用于生产端追踪和消费端幂等。
     */
    private String messageId;

    /**
     * 生产消息的服务名称，用于区分共享 Outbox 表中的消息归属。
     */
    private String producerService;

    /**
     * 业务键，例如库存预扣流水号 deductNo。
     */
    private String bizKey;

    /**
     * 消息类型，例如订单创建成功或订单创建失败。
     */
    private String messageType;

    /**
     * RabbitMQ 目标交换机名称。
     */
    private String exchangeName;

    /**
     * RabbitMQ 路由键。
     */
    private String routingKey;

    /**
     * 序列化后的消息 JSON 内容。
     */
    private String content;

    /**
     * 发送状态：0-待发送，10-发送中，20-已确认，30-待重试，40-死亡。
     */
    private Integer status;

    /**
     * 已失败的发送次数。
     */
    private Integer retryCount;

    /**
     * 下次允许执行发送重试的时间。
     */
    private LocalDateTime nextRetryTime;

    /**
     * 当前发送任务的抢占租约截止时间，防止多个服务实例同时投递同一条消息。
     */
    private LocalDateTime claimUntil;

    /**
     * 最近一次发送失败的错误信息。
     */
    private String lastError;

    /**
     * RabbitMQ Broker 确认接收消息的时间。
     */
    private LocalDateTime sentAt;

    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
