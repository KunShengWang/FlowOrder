package com.javaup.resource.dto;

/**
 * FlowOrder 根据领域事实给出的确定性案例分类。
 *
 * <p>Agent 可以解释分类和补充 SOP，但不能覆盖这里的交易规则判断。</p>
 */
public enum RecoveryCaseDiagnosis {

    ALREADY_CONVERGED,
    REPLAY_CANDIDATE,
    ACTION_IN_PROGRESS,
    DEPENDENCY_UNAVAILABLE,
    FACT_CONFLICT,
    UNSUPPORTED_EVENT,
    NO_RECOVERY_EVIDENCE
}
