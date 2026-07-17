package com.javaup.resource.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 面向 OrderCare 的稳定只读案例契约。
 *
 * <p>只返回诊断所需事实，不返回死信原文、任意 URL 或数据库实体。</p>
 */
@Data
public class RecoveryCaseResult {

    private String schemaVersion = "floworder-recovery-case-v1";
    private String caseKey;
    private String identifierType;
    private String identifierValue;
    private String canonicalRequestId;
    private Boolean found;
    private String diagnosisCode;
    private Boolean factsComplete;
    private Boolean recoveryEligible;
    private LocalDateTime generatedAt;
    private ReservationFact reservation;
    private OrderFact order;
    private DeductFact deduct;
    private InventoryFact inventory;
    private List<DeadLetterFact> deadLetters = new ArrayList<>();
    private List<RecoveryActionFact> recoveryActions = new ArrayList<>();
    private List<RecoveryCandidate> candidates = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();
    private List<String> hardRisks = new ArrayList<>();

    @Data
    public static class ReservationFact {
        private Boolean exists;
        private Long id;
        private String requestId;
        private String traceId;
        private Integer status;
        private String statusName;
        private String orderNo;
        private Integer orderStatus;
        private String orderStatusName;
        private String latestOrderEventType;
        private LocalDateTime latestOrderEventTime;
        private Integer orderEventVersion;
        private String lastError;
    }

    @Data
    public static class OrderFact {
        private Boolean dependencyAvailable;
        private Boolean exists;
        private String orderNo;
        private Integer status;
        private String statusName;
        private String queryError;
    }

    @Data
    public static class DeductFact {
        private Boolean exists;
        private Long id;
        private String deductNo;
        private String orderNo;
        private Long stockItemId;
        private Integer quantity;
        private Integer status;
        private String statusName;
        private String releaseReason;
        private String lastError;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class InventoryFact {
        private Boolean exists;
        private Long stockItemId;
        private Integer totalStock;
        private Integer availableStock;
        private Integer lockedStock;
        private Integer soldStock;
        private Integer invariantDiff;
        private Boolean invariantOk;
        private Integer version;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class DeadLetterFact {
        private Long deadLetterId;
        private String messageId;
        private String deadQueue;
        private String producerService;
        private String messageType;
        private String bizKey;
        private Integer status;
        private String statusName;
        private Integer replayCount;
        private String deathReason;
        private String lastError;
        private LocalDateTime replayedAt;
        private LocalDateTime resolvedAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RecoveryActionFact {
        private Long actionId;
        private String actionRequestId;
        private String actionType;
        private String targetType;
        private String targetKey;
        private Integer status;
        private String statusName;
        private String executionOwner;
        private LocalDateTime executionLeaseUntil;
        private LocalDateTime lastHeartbeatAt;
        private Integer reconcileCount;
        private String lastError;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RecoveryCandidate {
        private String candidateId;
        private String actionType;
        private String targetType;
        private String targetKey;
        private Boolean eligible;
        private String decisionOwner;
        private String blockedBy;
    }
}
