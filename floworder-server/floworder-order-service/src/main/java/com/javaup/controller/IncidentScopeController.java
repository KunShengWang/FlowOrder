package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderScopeCandidateRequest;
import com.javaup.dto.OrderScopeCandidateResponse;
import com.javaup.security.InternalIncidentScopeAuthorizer;
import com.javaup.service.IncidentOrderScopeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/incidents/scopes")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "floworder.admin", name = "enabled", havingValue = "true")
public class IncidentScopeController {

    private final IncidentOrderScopeQueryService queryService;
    private final InternalIncidentScopeAuthorizer authorizer;

    @PostMapping("/order-candidates")
    public ApiResponse<OrderScopeCandidateResponse> orderCandidates(
            @RequestHeader(InternalIncidentScopeAuthorizer.HEADER) String internalToken,
            @RequestBody OrderScopeCandidateRequest request) {
        authorizer.authorize(internalToken);
        return ApiResponse.success(queryService.discover(request));
    }
}
