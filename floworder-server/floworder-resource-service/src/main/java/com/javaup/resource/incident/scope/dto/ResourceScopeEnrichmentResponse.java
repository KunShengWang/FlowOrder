package com.javaup.resource.incident.scope.dto;

import com.javaup.dto.IncidentAnomalyType;
import com.javaup.dto.IncidentSourceReference;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ResourceScopeEnrichmentResponse {
    private String discoveryRequestId;
    private LocalDateTime observedAt;
    private List<Item> items;
    private List<String> queueNames;
    private Map<String, String> sourceHealth;

    @Data
    public static class Item {
        private String requestId;
        private String orderNo;
        private String deductNo;
        private Integer reservationStatus;
        private Integer deductStatus;
        private String releaseState;
        private Long stockItemId;
        private Integer stockAvailable;
        private Integer stockLocked;
        private List<IncidentAnomalyType> anomalyTypes;
        private List<DeadLetter> deadLetters;
        private RelationQuality relationQuality;
        private String completeness;
        private List<IncidentSourceReference> sourceReferences;
    }

    @Data
    public static class DeadLetter {
        private Long deadLetterId;
        private String messageId;
        private String deadQueue;
        private String exchange;
        private String routingKey;
        private String messageType;
        private Integer status;
        private RelationQuality relationQuality;
        private LocalDateTime observedAt;
        private List<IncidentSourceReference> sourceReferences;
    }
}
