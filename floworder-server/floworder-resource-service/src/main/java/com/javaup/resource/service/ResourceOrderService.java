package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;

public interface ResourceOrderService {

    /**
     * V1版本创建订单
     */
    String createV1(ResourceOrderCreateDto createDto);

    /**
     * V2版本创建订单
     */
    String createV2(ResourceOrderCreateDto createDto);

    /**
     * V3版本创建订单
     */
    String createV3(ResourceOrderCreateDto createDto);

    String createInstantAfterAdmission(
            ResourceOrderCreateDto createDto,
            Long requestDbId,
            String owner
    );
}
