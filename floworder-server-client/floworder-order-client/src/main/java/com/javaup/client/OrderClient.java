package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.constant.Constant;
import com.javaup.dto.CreateOrderDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 订单服务 feign
 */
@FeignClient(value = Constant.FLOWORDER_ORDER_SERVICE,fallback = OrderClientFallback.class,url = "http://localhost:8080")
public interface OrderClient {

    /**
     * 创建订单
     */
    @PostMapping(value = "/order/create")
    ApiResponse<String> create(@RequestBody CreateOrderDto createOrderDto);
}
