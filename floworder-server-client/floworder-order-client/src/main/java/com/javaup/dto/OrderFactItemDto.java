package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderFactItemDto {

    private String requestId;
    private Boolean exists;
    private String orderNo;
    private String deductNo;
    private Integer status;
    private LocalDateTime updatedAt;
}
