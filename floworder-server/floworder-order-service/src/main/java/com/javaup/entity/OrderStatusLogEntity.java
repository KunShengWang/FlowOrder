package com.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态流转日志表实体 (fo_order_status_log)
 */
@Data
@TableName("fo_order_status_log")
public class OrderStatusLogEntity {

    /** 主键ID */
    private Long id;

    /** 预约单号 */
    private String orderNo;

    /** 原状态 */
    private Integer fromStatus;

    /** 目标状态 */
    private Integer toStatus;

    /** 事件：CREATE/CONFIRM/CANCEL/TIMEOUT/FAIL */
    private String event;

    /** 操作方：SYSTEM/USER/MQ */
    private String operatorType;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;
}