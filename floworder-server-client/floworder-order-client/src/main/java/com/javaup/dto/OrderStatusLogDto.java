package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderStatusLogDto {
    private Integer fromStatus;
    private Integer toStatus;
    private String event;
    private String operatorType;
    private String remark;
    private LocalDateTime createdAt;
}