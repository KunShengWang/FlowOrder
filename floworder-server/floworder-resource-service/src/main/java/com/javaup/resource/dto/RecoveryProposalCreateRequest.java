package com.javaup.resource.dto;

import lombok.Data;

@Data
public class RecoveryProposalCreateRequest {

    /** enterprise-agent 在首次 preview 前生成；网络重试必须复用同一个 proposalId。 */
    private String proposalId;

    private String identifierType;

    private String identifierValue;

    /** V1 仅开放 REPLAY。目标由 FlowOrder 候选动作推导，调用方不能指定。 */
    private String actionType;

    /** Agent 建议理由，不等同于人工审批意见。 */
    private String suggestedReason;
}
