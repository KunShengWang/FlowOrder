package com.javaup.dto;

import lombok.Data;

@Data
public class OrderConfirmDto {

    private String orderNo;
    private Long userId;
}