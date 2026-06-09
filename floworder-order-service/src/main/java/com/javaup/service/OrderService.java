package com.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.javaup.dto.CreateOrderDto;
import com.javaup.entity.ReservationOrderEntity;

public interface OrderService extends IService<ReservationOrderEntity> {

    String create(CreateOrderDto createOrderDto);
}
