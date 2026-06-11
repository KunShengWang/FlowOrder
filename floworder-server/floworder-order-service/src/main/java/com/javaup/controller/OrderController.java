package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    /**
     * 创建订单
     */
    @PostMapping(value = "/create")
    public ApiResponse<String> create(@RequestBody CreateOrderDto createOrderDto) {
        return ApiResponse.success(orderService.create(createOrderDto));
    }

    /**
     * 订单查询
     */
    @GetMapping("/query")
    public ApiResponse<OrderQueryDto> queryByRequestId(@RequestParam("requestId") String requestId){
        return ApiResponse.success(orderService.queryByRequestId(requestId));
    }
}
