package com.javaup.dto;

import lombok.Data;

/**
 * 订单查询 dto
 */
@Data
public class OrderQueryDto {

    private Boolean exists;

    private String orderNo;

    private Integer status;
}
