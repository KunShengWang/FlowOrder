package com.javaup.dto;

import lombok.Data;

@Data
public class ResourceOrderCreateDto {

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
     * 请求ID
     */
    private String requestId;
}
