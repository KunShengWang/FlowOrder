package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fo_user_reservation_quota")
public class UserReservationQuotaEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long resourceId;

    private Long stockItemId;

    private Long userId;

    /** 资格状态：1有效 0无效 */
    private Integer status;

    /** 累计限购数量 */
    private Integer limitQuantity;

    /** 已预扣或已成交数量 */
    private Integer usedQuantity;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}