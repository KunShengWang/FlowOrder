package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.constant.Constant;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务 feign
 */
@FeignClient(
        value = Constant.FLOWORDER_ORDER_SERVICE,
        fallbackFactory = OrderClientFallbackFactory.class
)
public interface OrderClient {

    /**
     * 创建订单
     */
    @PostMapping(value = "/order/create")
    ApiResponse<String> create(@RequestBody CreateOrderDto createOrderDto);

    /**
     * 订单查询
     */
    @GetMapping("/order/query")
    ApiResponse<OrderQueryDto> queryByRequestId(@RequestParam("requestId") String requestId);
}
