package com.javaup.resource.service.strategy;

import com.javaup.dto.ResourceOrderCreateDto;

public interface ResourceOrderStrategy {

    /**
     * 创建订单
     * */
    String createOrder(ResourceOrderCreateDto createDto);

    /**
     * 获取购买策略的版本号
     */
    String version();
}
