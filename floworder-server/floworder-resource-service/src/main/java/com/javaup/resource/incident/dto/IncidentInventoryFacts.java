package com.javaup.resource.incident.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IncidentInventoryFacts {

    private Integer recordCount;
    private Integer distinctRequestIdCount;
    private Integer unreleasedDistinctRequestIdCount;
    private List<String> requestIds;
    private List<String> unreleasedRequestIds;
    private List<Long> invariantViolationStockItemIds;
    private List<InventoryFact> items;

    @Data
    public static class InventoryFact {
        private String requestId;
        private String deductNo;
        private Integer deductStatus;
        private Integer quantity;
        private Long stockItemId;
        private Boolean stockItemFound;
        private Integer totalStock;
        private Integer availableStock;
        private Integer lockedStock;
        private Integer soldStock;
        private Boolean inventoryInvariantOk;
        private LocalDateTime updatedAt;
    }
}
