package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存项表实体 (fo_stock_item)
 */
@Data
@TableName("fo_stock_item")
public class StockItemEntity {

    /** 主键ID */
    private Long id;

    /** 库存项编码 */
    private String stockItemCode;

    /** 资源ID */
    private Long resourceId;

    /** 库存项名称 */
    private String name;

    /** 总库存 */
    private Integer totalStock;

    /** 可用库存，MySQL兜底/展示用 */
    private Integer availableStock;

    /** 已锁定库存 */
    private Integer lockedStock;

    /** 已确认库存 */
    private Integer soldStock;

    /** 状态：1启用 0禁用 */
    private Integer status;

    /** 预约开始时间 */
    private LocalDateTime startTime;

    /** 预约结束时间 */
    private LocalDateTime endTime;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除：0未删除 1已删除 */
    private Integer deleted;
}