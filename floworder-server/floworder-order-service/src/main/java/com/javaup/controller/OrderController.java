package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @PostMapping(value = "/create")
    public ApiResponse<String> create(@RequestBody CreateOrderDto createOrderDto) {
        return ApiResponse.success(orderService.create(createOrderDto));
    }
}
