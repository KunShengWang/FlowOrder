package com.javaup.resource.incident.controller;

import com.javaup.common.ApiResponse;
import com.javaup.resource.incident.dto.IncidentDeadLetterFacts;
import com.javaup.resource.incident.dto.IncidentFactQueryRequest;
import com.javaup.resource.incident.dto.IncidentFactResponse;
import com.javaup.resource.incident.dto.IncidentInventoryFacts;
import com.javaup.resource.incident.dto.IncidentOrderFacts;
import com.javaup.resource.incident.service.IncidentFactQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/incidents/facts")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "floworder.admin", name = "enabled", havingValue = "true")
public class IncidentFactController {

    private final IncidentFactQueryService factQueryService;

    @PostMapping("/orders")
    public ApiResponse<IncidentFactResponse<IncidentOrderFacts>> orders(
            @RequestBody IncidentFactQueryRequest request) {
        return ApiResponse.success(factQueryService.queryOrders(request));
    }

    @PostMapping("/inventory")
    public ApiResponse<IncidentFactResponse<IncidentInventoryFacts>> inventory(
            @RequestBody IncidentFactQueryRequest request) {
        return ApiResponse.success(factQueryService.queryInventory(request));
    }

    @PostMapping("/dead-letters")
    public ApiResponse<IncidentFactResponse<IncidentDeadLetterFacts>> deadLetters(
            @RequestBody IncidentFactQueryRequest request) {
        return ApiResponse.success(factQueryService.queryDeadLetters(request));
    }
}
