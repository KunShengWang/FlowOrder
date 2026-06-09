package com.javaup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约订单表实体 (fo_reservation_order)
 */
@Data
@TableName("fo_reservation_order")
public class ReservationOrderEntity {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 预约单号 */
    private String orderNo;

    /** 用户ID */
    private Long userId;

    /** 资源ID */
    private Long resourceId;

    /** 库存项ID */
    private Long stockItemId;

    /** 预约数量 */
    private Integer quantity;

    /** 订单状态：0初始化 10已预约 20已确认 30已取消 40已超时 50失败 */
    private Integer status;

    /** 请求幂等ID */
    private String requestId;

    /** 库存预扣流水号 */
    private String deductNo;

    /** 超时时间 */
    private LocalDateTime expireTime;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 取消时间 */
    private LocalDateTime canceledAt;

    /** 取消原因 */
    private String cancelReason;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除：0未删除 1已删除 */
    private Integer deleted;
}
