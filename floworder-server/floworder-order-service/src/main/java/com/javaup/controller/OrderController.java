package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.*;
import com.javaup.service.OrderService;
import com.javaup.service.OrderStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private OrderStateService orderStateService;

    /**
     * 创建订单
     */
    @PostMapping(value = "/create")
    public ApiResponse<String> create(@RequestBody CreateOrderDto createOrderDto) {
        log.info(
                "收到订单创建请求, userId={}, resourceId={}, stockItemId={}, quantity={}, requestId={}",
                createOrderDto.getUserId(),
                createOrderDto.getResourceId(),
                createOrderDto.getStockItemId(),
                createOrderDto.getQuantity(),
                createOrderDto.getRequestId()
        );
        return ApiResponse.success(orderService.create(createOrderDto));
    }

    /**
     * 订单查询
     */
    @GetMapping("/query")
    public ApiResponse<OrderQueryDto> queryByRequestId(@RequestParam("requestId") String requestId) {
        log.info("收到订单查询请求, requestId={}", requestId);
        return ApiResponse.success(orderService.queryByRequestId(requestId));
    }

    /**
     * 订单确认
     */
    @PostMapping("/confirm")
    public ApiResponse<Void> confirm(@RequestBody OrderConfirmDto dto) {
        orderStateService.confirm(dto.getOrderNo(), dto.getUserId());
        return ApiResponse.success();
    }

    /**
     * 订单取消
     */
    @PostMapping("/cancel")
    public ApiResponse<Void> cancel(@RequestBody OrderCancelDto dto) {
        orderStateService.cancel(dto.getOrderNo(), dto.getUserId(), dto.getReason());
        return ApiResponse.success();
    }

    @GetMapping("/detail")
    public ApiResponse<OrderStateDetailDto> detail(@RequestParam String orderNo, @RequestParam Long userId) {
        return ApiResponse.success(orderStateService.detail(orderNo, userId));
    }
}
