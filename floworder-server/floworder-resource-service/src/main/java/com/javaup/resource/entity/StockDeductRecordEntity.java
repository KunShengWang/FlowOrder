package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存预扣记录表实体 (fo_stock_deduct_record)
 */
@Data
@TableName("fo_stock_deduct_record")
public class StockDeductRecordEntity {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 库存预扣流水号 */
    private String deductNo;

    /** 预约单号 */
    private String orderNo;

    /** 用户ID */
    private Long userId;

    /** 资源ID */
    private Long resourceId;

    /** 库存项ID */
    private Long stockItemId;

    /** 预扣数量 */
    private Integer quantity;

    /** 请求幂等ID */
    private String requestId;

    /** 状态：10已预扣 20已确认 30已释放 40失败 50人工确认 */
    private Integer status;

    /** 预扣过期时间 */
    private LocalDateTime expireTime;

    /** 释放原因 */
    private String releaseReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 订单不存在确认次数 */
    private Integer retryCount;

    /** 下次确认时间 */
    private LocalDateTime nextRetryTime;

    /** 最后确认结果 */
    private String lastError;

    /** 订单查询异常次数 */
    private Integer queryErrorCount;

    /** 创建模式：2同步 3异步 */
    private Integer createMode;
}
