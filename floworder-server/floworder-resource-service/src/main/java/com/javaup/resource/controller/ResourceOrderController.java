package com.javaup.resource.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.ResourceOrderVersionEnum;
import com.javaup.resource.service.strategy.ResourceOrderContext;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import static com.javaup.trace.TraceConstant.REQUEST_ID;

@Slf4j
@RestController
@RequestMapping("/reservation")
public class ResourceOrderController {

    @Resource
    private ResourceOrderContext context;

    @PostMapping("/create/v1")
    public ApiResponse<String> createOrderV1(@RequestBody ResourceOrderCreateDto createDto) {
        return create(createDto, ResourceOrderVersionEnum.V1_VERSION);
    }

    @PostMapping("/create/v2")
    public ApiResponse<String> createOrderV2(@RequestBody ResourceOrderCreateDto createDto) {
        return create(createDto, ResourceOrderVersionEnum.V2_VERSION);
    }

    @PostMapping("/create/v3")
    public ApiResponse<String> createOrderV3(@RequestBody ResourceOrderCreateDto createDto) {
        return create(createDto, ResourceOrderVersionEnum.V3_VERSION);
    }

    private ApiResponse<String> create(ResourceOrderCreateDto createDto, ResourceOrderVersionEnum version) {
        MDC.put(REQUEST_ID, createDto.getRequestId());
        try {
            log.info(
                    "收到预约创建请求, version={}, userId={}, resourceId={}, stockItemId={}, quantity={}, requestId={}",
                    version.getVersion(),
                    createDto.getUserId(),
                    createDto.getResourceId(),
                    createDto.getStockItemId(),
                    createDto.getQuantity(),
                    createDto.getRequestId()
            );
            return ApiResponse.success(context.get(version.getVersion()).createOrder(createDto));
        } finally {
            MDC.remove(REQUEST_ID);
        }
    }
}
