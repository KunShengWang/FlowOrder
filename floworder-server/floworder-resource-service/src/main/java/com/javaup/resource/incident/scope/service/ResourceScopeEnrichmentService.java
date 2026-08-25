package com.javaup.resource.incident.scope.service;

import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentRequest;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentResponse;

public interface ResourceScopeEnrichmentService {
    ResourceScopeEnrichmentResponse enrich(ResourceScopeEnrichmentRequest request);
}
