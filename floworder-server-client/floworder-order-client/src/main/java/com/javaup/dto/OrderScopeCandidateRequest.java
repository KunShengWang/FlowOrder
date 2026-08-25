package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderScopeCandidateRequest {
    private String discoveryRequestId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<IncidentAnomalyType> anomalyTypes;
    private List<String> explicitOrderNos;
    private Integer limit;
    private String cursor;
}
