package com.javaup.resource.incident.scope.dto;

import com.javaup.dto.IncidentAnomalyType;
import lombok.Data;

import java.util.List;

@Data
public class ResourceScopeEnrichmentRequest {
    private String discoveryRequestId;
    private List<String> requestIds;
    private List<String> deductNos;
    private List<Long> deadLetterIds;
    private List<IncidentAnomalyType> anomalyTypes;
}
