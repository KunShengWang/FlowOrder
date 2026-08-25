package com.javaup.service;

import com.javaup.dto.OrderScopeCandidateRequest;
import com.javaup.dto.OrderScopeCandidateResponse;

public interface IncidentOrderScopeQueryService {
    OrderScopeCandidateResponse discover(OrderScopeCandidateRequest request);
}
