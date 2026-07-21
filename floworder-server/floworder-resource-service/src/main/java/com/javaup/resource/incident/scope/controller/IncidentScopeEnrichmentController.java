package com.javaup.resource.incident.scope.controller;

import com.javaup.common.ApiResponse;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentRequest;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentResponse;
import com.javaup.resource.incident.scope.service.ResourceScopeEnrichmentService;
import com.javaup.security.InternalIncidentScopeAuthorizer;
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
public class IncidentScopeEnrichmentController {

    private final ResourceScopeEnrichmentService enrichmentService;
    private final InternalIncidentScopeAuthorizer authorizer;

    @PostMapping("/resource-enrichment")
    public ApiResponse<ResourceScopeEnrichmentResponse> enrich(
            @RequestHeader(InternalIncidentScopeAuthorizer.HEADER) String internalToken,
            @RequestBody ResourceScopeEnrichmentRequest request) {
        authorizer.authorize(internalToken);
        return ApiResponse.success(enrichmentService.enrich(request));
    }
}
