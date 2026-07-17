package com.javaup.resource.service;

import com.javaup.resource.dto.RecoveryActionReconcileRequest;
import com.javaup.resource.dto.RecoveryActionResult;
import com.javaup.resource.dto.RecoveryProposalCreateRequest;
import com.javaup.resource.dto.RecoveryProposalExecuteRequest;
import com.javaup.resource.dto.RecoveryProposalResult;

public interface RecoveryProposalService {

    RecoveryProposalResult create(RecoveryProposalCreateRequest request);

    RecoveryProposalResult find(String proposalId);

    RecoveryProposalResult execute(RecoveryProposalExecuteRequest request);

    RecoveryActionResult findAction(String actionRequestId);

    RecoveryActionResult reconcileAction(
            String actionRequestId,
            RecoveryActionReconcileRequest request
    );
}
