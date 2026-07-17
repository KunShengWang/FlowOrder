package com.javaup.resource.dto;

import lombok.Data;

@Data
public class RecoveryProposalExecuteRequest {

    private String proposalId;

    /** 以下字段来自审批时保存的不可变 Proposal 快照，不能由模型在恢复时重新生成。 */
    private Integer proposalVersion;

    private String stateFingerprint;

    private String effectsDigest;

    private String warningsDigest;

    private String previewDigest;

    private String approvalId;

    private String approvedBy;

    private String approvalComment;

    /** enterprise-agent 服务端注入的 toolExecutionId，模型不可生成。 */
    private String executionOwner;
}
