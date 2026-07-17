package com.javaup.resource.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Recovery Action 的权威查询与对账结果。 */
@Data
public class RecoveryActionResult {

    private String schemaVersion = "floworder-recovery-action-v1";
    private String proposalId;
    private String actionRequestId;
    private String actionType;
    private String targetType;
    private String targetKey;
    private String actionStatus;
    private String caseOutcome;
    private String reconciliationStatus;
    private String executionOwner;
    private LocalDateTime executionLeaseUntil;
    private LocalDateTime lastHeartbeatAt;
    private Boolean leaseExpired;
    private Integer reconcileCount;
    private String lastError;
    private String executeResult;
    private LocalDateTime reconciledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
