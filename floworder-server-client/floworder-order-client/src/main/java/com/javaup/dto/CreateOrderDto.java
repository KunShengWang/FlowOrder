package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateOrderDto {

    /**
     * 预约单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 资源ID
     */
    private Long resourceId;

    /**
     * 库存项ID
     */
    private Long stockItemId;

    /**
     * 预约数量
     */
    private Integer quantity;

    /**
     * 请求幂等ID
     */
    private String requestId;

    /**
     * 库存预扣流水号
     */
    private String deductNo;

    /**
     * 超时时间
     */
    private LocalDateTime expireTime;
}
