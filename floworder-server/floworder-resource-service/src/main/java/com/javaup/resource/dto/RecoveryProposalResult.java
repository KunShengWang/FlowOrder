package com.javaup.resource.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Proposal、动作提交和业务结果是三个独立维度，禁止复用一个 status 表达。
 */
@Data
public class RecoveryProposalResult {

    private String schemaVersion = "floworder-recovery-proposal-v1";
    private String proposalId;
    private Integer proposalVersion;
    private String proposalStatus;
    private String actionRequestId;
    private String actionStatus;
    private String caseOutcome;
    private String caseKey;
    private String identifierType;
    private String identifierValue;
    private String actionType;
    private String targetType;
    private String targetKey;
    private String stateFingerprint;
    private String effectsDigest;
    private String warningsDigest;
    private String previewDigest;
    private Boolean canExecute;
    private List<String> effects = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private String suggestedReason;
    private String approvalId;
    private String approvedBy;
    private String approvalComment;
    private LocalDateTime approvedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
