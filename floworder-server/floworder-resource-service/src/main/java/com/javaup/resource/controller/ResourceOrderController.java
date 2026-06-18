package com.javaup.resource.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.ResourceOrderVersionEnum;
import com.javaup.resource.service.ResourceOrderV3Service;
import com.javaup.resource.service.strategy.ResourceOrderContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/reservation")
public class ResourceOrderController {

    @Resource
    private ResourceOrderContext context;

    @Resource
    private ResourceOrderV3Service v3Service;

    @PostMapping("/create/v1")
    public ApiResponse<String> createOrderV1(@RequestBody ResourceOrderCreateDto createDto){
        return ApiResponse.<String>success(context.get(ResourceOrderVersionEnum.V1_VERSION.getVersion()).createOrder(createDto));
    }

    @PostMapping("/create/v2")
    public ApiResponse<String> createOrderV2(@RequestBody ResourceOrderCreateDto createDto){
        return ApiResponse.success(context.get(ResourceOrderVersionEnum.V2_VERSION.getVersion()).createOrder(createDto));
    }

    @PostMapping("/create/v3")
    public ApiResponse<String> createOrderV3(@RequestBody ResourceOrderCreateDto createDto){
        log.info("收到订单创建请求，userId = {},resourceId = {},stockItemId = {},quantity = {},requestId = {}",
                createDto.getUserId(),createDto.getResourceId(),createDto.getStockItemId(),createDto.getQuantity(),createDto.getRequestId());
        return ApiResponse.success(v3Service.createOrder(createDto));
    }
}
