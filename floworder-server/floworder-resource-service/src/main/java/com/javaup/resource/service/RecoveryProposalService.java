package com.javaup.resource.service;

import com.javaup.resource.dto.RecoveryProposalCreateRequest;
import com.javaup.resource.dto.RecoveryProposalExecuteRequest;
import com.javaup.resource.dto.RecoveryProposalResult;

public interface RecoveryProposalService {

    RecoveryProposalResult create(RecoveryProposalCreateRequest request);

    RecoveryProposalResult find(String proposalId);

    RecoveryProposalResult execute(RecoveryProposalExecuteRequest request);
}
