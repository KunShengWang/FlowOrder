package com.javaup.resource.dto;

import lombok.Data;

@Data
public class RecoveryActionReconcileRequest {

    /** 发起本次对账/接管的稳定实例或 toolExecutionId。 */
    private String executionOwner;
}
