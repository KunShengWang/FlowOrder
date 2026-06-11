package com.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.entity.ReservationOrderEntity;

public interface OrderService extends IService<ReservationOrderEntity> {

    /**
     * 创建订单
     */
    String create(CreateOrderDto createOrderDto);

    /**
     * 订单查询
     */
    OrderQueryDto queryByRequestId(String requestId);
}
