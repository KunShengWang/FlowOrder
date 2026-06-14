package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ 消费幂等记录。
 *
 * <p>消费者在执行业务前写入记录，并通过 messageId 和 consumerGroup 的联合唯一索引，
 * 防止 RabbitMQ 重复投递导致同一业务被重复执行。</p>
 */
@Data
@TableName("fo_mq_consume_log")
public class MqConsumeLogEntity {

    /**
     * 主键，使用 MyBatis-Plus 雪花算法生成。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 被消费消息的唯一 ID。
     */
    private String messageId;

    /**
     * 消费者组名称，同一消息允许被不同消费者组分别消费一次。
     */
    private String consumerGroup;

    /**
     * 消息类型，例如订单创建成功或订单创建失败。
     */
    private String messageType;

    /**
     * 业务键，例如库存预扣流水号 deductNo。
     */
    private String bizKey;

    /**
     * 消费状态：0-处理中，10-消费成功。
     */
    private Integer status;

    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
