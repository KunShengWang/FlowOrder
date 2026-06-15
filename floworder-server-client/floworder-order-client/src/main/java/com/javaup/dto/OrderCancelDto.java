package com.javaup.dto;

import lombok.Data;

@Data
public class OrderCancelDto {

    private String orderNo;
    private Long userId;
    private String reason;
}