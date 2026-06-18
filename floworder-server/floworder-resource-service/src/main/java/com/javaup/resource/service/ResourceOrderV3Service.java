package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;

public interface ResourceOrderV3Service {

    /**
     * v3版本创建订单
     */
    String createOrder(ResourceOrderCreateDto createDto);
}
