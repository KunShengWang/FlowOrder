package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.exception.RemoteCallException;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderClientFallbackFactory implements FallbackFactory<OrderClient> {

    @Override
    public OrderClient create(Throwable cause) {
        return new OrderClient() {
            @Override
            public ApiResponse<String> create(CreateOrderDto createOrderDto) {
                throw new RemoteCallException("订单服务不可用，创建结果未知", cause);
            }

            @Override
            public ApiResponse<OrderQueryDto> queryByRequestId(String requestId) {
                throw new RemoteCallException("订单服务不可用，查询结果未知", cause);
            }
        };
    }
}
