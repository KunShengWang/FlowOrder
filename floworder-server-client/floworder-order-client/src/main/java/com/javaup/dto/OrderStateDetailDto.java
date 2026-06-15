package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderStateDetailDto {
    private String orderNo;
    private Long userId;
    private Long stockItemId;
    private Integer quantity;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime confirmedAt;
    private LocalDateTime canceledAt;
    private String cancelReason;
    private Integer version;
    private List<OrderStatusLogDto> statusLogs;
}