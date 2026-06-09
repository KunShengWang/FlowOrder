package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;

public interface ResourceOrderService {

    /**
     * V1版本创建订单
     */
    String createV1(ResourceOrderCreateDto createDto);
}
