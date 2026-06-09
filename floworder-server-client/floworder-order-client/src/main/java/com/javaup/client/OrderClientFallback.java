package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.enums.BaseCodeEnum;
import org.springframework.stereotype.Component;

@Component
public class OrderClientFallback implements OrderClient{

    @Override
    public ApiResponse<String> create(CreateOrderDto createOrderDto) {
        return ApiResponse.error(BaseCodeEnum.SYSTEM_ERROR);
    }
}
