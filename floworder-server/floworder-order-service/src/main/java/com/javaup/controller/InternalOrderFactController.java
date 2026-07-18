package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderFactBatchRequest;
import com.javaup.dto.OrderFactBatchResult;
import com.javaup.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orders/facts")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "floworder.admin", name = "enabled", havingValue = "true")
public class InternalOrderFactController {

    private final OrderService orderService;

    @PostMapping("/query")
    public ApiResponse<OrderFactBatchResult> query(@RequestBody OrderFactBatchRequest request) {
        return ApiResponse.success(orderService.queryFacts(request));
    }
}
