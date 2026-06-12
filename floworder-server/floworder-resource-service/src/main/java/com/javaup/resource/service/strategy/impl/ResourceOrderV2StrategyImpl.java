package com.javaup.resource.service.strategy.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.CompositeCheckTypeEnum;
import com.javaup.enums.ResourceOrderVersionEnum;
import com.javaup.initialize.impl.composite.CompositeContainer;
import com.javaup.resource.service.ResourceOrderService;
import com.javaup.resource.service.strategy.ResourceOrderStrategy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class ResourceOrderV2StrategyImpl implements ResourceOrderStrategy {

    @Resource
    private CompositeContainer compositeContainer;

    @Resource
    private ResourceOrderService orderService;

    @Override
    public String createOrder(ResourceOrderCreateDto createDto) {
        // 创建订单前的校验
        compositeContainer.execute(CompositeCheckTypeEnum.PROGRAM_ORDER_CREATE_CHECK.getValue(),createDto);
        return orderService.createV2(createDto);
    }

    @Override
    public String version() {
        return ResourceOrderVersionEnum.V2_VERSION.getVersion();
    }
}
