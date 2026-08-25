package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderScopeCandidateItem {
    private String requestId;
    private String orderNo;
    private String deductNo;
    private Integer orderStatus;
    private Integer reservationStatus;
    private LocalDateTime observedAt;
    private List<IncidentAnomalyType> anomalyTypes;
    private List<IncidentSourceReference> sourceReferences;
}
