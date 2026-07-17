package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 恢复预演与审批对象。Proposal 是不可变预演的权威事实，Action Request 是副作用幂等命令。
 */
@Data
@TableName("fo_recovery_proposal")
public class RecoveryProposalEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String proposalId;

    private Integer proposalVersion;

    private String actionRequestId;

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

    /** 0 ACTIVE, 10 APPROVED, 20 REJECTED, 30 EXPIRED, 40 INVALIDATED。 */
    private Integer status;

    private String effectsJson;

    private String warningsJson;

    private String suggestedReason;

    private String approvalId;

    private String approvedBy;

    private String approvalComment;

    private LocalDateTime approvedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
