package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fo_reservation_request")
public class ReservationRequestEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;
    private String traceId;

    private Long userId;
    private Long resourceId;
    private Long stockItemId;
    private Integer quantity;

    private String orderNo;
    private Integer status;
    private Integer orderStatus;
    private String latestOrderEventType;
    private LocalDateTime latestOrderEventTime;
    private Integer orderEventVersion;

    private Integer retryCount;
    private LocalDateTime nextRetryTime;

    private String claimOwner;
    private LocalDateTime claimUntil;

    private String lastError;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
