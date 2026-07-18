package com.javaup.resource.incident.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IncidentOrderFacts {

    private Integer recordCount;
    private Integer distinctRequestIdCount;
    private Integer terminalDistinctRequestIdCount;
    private List<String> requestIds;
    private List<String> terminalRequestIds;
    private List<OrderFact> items;

    @Data
    public static class OrderFact {
        private String requestId;
        private Boolean reservationExists;
        private Integer reservationStatus;
        private Boolean dependencyAvailable;
        private Boolean orderExists;
        private String orderNo;
        private String deductNo;
        private Integer orderStatus;
        private String latestEvent;
        private LocalDateTime latestEventTime;
        private LocalDateTime updatedAt;
    }
}
