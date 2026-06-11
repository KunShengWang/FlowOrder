package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class OrderClientFallback implements OrderClient{

    @Override
    public ApiResponse<String> create(CreateOrderDto createOrderDto) {
        throw new BizException("订单服务不可用，创建结果未知");
    }

    @Override
    public ApiResponse<OrderQueryDto> queryByRequestId(String requestId) {
        throw new BizException("订单服务调用结果未知");
    }
}
