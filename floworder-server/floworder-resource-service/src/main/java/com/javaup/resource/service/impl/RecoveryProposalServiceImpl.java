package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.exception.BizException;
import com.javaup.resource.dto.RecoveryCaseResult;
import com.javaup.resource.dto.RecoveryActionReconcileRequest;
import com.javaup.resource.dto.RecoveryActionResult;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryProposalCreateRequest;
import com.javaup.resource.dto.RecoveryProposalExecuteRequest;
import com.javaup.resource.dto.RecoveryProposalResult;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.entity.RecoveryProposalEntity;
import com.javaup.resource.mapper.RecoveryActionLogMapper;
import com.javaup.resource.mapper.RecoveryProposalMapper;
import com.javaup.resource.service.RecoveryCaseService;
import com.javaup.resource.service.RecoveryProposalService;
import com.javaup.resource.service.RecoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RecoveryProposalServiceImpl implements RecoveryProposalService {

    private static final int PROPOSAL_ACTIVE = 0;
    private static final int PROPOSAL_APPROVED = 10;
    private static final int PROPOSAL_REJECTED = 20;
    private static final int PROPOSAL_EXPIRED = 30;
    private static final int PROPOSAL_INVALIDATED = 40;

    private static final int ACTION_PREVIEWED = 0;
    private static final int ACTION_EXECUTING = 10;
    private static final int ACTION_SUBMITTED = 20;
    private static final int ACTION_FAILED = 30;
    private static final int ACTION_MANUAL_REVIEW = 40;

    private static final int DEAD_PENDING = 0;
    private static final int DEAD_REPLAYING = 10;
    private static final String ACTION_REPLAY = "REPLAY";
    private static final String TARGET_DEAD_LETTER = "DEAD_LETTER";

    private final RecoveryProposalMapper proposalMapper;
    private final RecoveryActionLogMapper actionLogMapper;
    private final RecoveryCaseService caseService;
    private final RecoveryService recoveryService;
    private final ObjectMapper objectMapper;
    private final long proposalTtlSeconds;
    private final Clock clock;

    @Autowired
    public RecoveryProposalServiceImpl(
            RecoveryProposalMapper proposalMapper,
            RecoveryActionLogMapper actionLogMapper,
            RecoveryCaseService caseService,
            RecoveryService recoveryService,
            ObjectMapper objectMapper,
            @Value("${floworder.recovery.proposal-ttl-seconds:600}") long proposalTtlSeconds
    ) {
        this(proposalMapper, actionLogMapper, caseService, recoveryService, objectMapper,
                proposalTtlSeconds, Clock.systemDefaultZone());
    }

    RecoveryProposalServiceImpl(
            RecoveryProposalMapper proposalMapper,
            RecoveryActionLogMapper actionLogMapper,
            RecoveryCaseService caseService,
            RecoveryService recoveryService,
            ObjectMapper objectMapper,
            long proposalTtlSeconds,
            Clock clock
    ) {
        this.proposalMapper = proposalMapper;
        this.actionLogMapper = actionLogMapper;
        this.caseService = caseService;
        this.recoveryService = recoveryService;
        this.objectMapper = objectMapper;
        this.proposalTtlSeconds = Math.max(30, proposalTtlSeconds);
        this.clock = clock;
    }

    @Override
    public RecoveryProposalResult create(RecoveryProposalCreateRequest request) {
        validateCreate(request);
        RecoveryProposalEntity existing = findByProposalId(request.getProposalId());
        if (existing != null) {
            ensureSameCreateRequest(existing, request);
            return toResult(existing, inspect(existing));
        }

        RecoveryCaseResult snapshot = caseService.inspect(request.getIdentifierType(), request.getIdentifierValue());
        RecoveryCaseResult.RecoveryCandidate candidate = requireEligibleCandidate(snapshot, request.getActionType());
        List<String> effects = List.of(
                "将死信 " + candidate.getTargetKey() + " 对应的原始消息重新提交到既有可靠消息链路",
                "不会由 Agent 直接修改订单、扣减或库存终态",
                "执行后必须由确定性收敛检查确认扣减已释放、库存守恒且相关死信已终结"
        );
        List<String> warnings = List.of(
                "该动作具有业务副作用，必须审批当前 Proposal 版本后才能执行",
                "Proposal 过期或业务状态指纹变化后，原审批自动失效",
                "命令已提交不等于业务已恢复，caseOutcome 需要独立回查"
        );
        LocalDateTime now = LocalDateTime.now(clock);
        RecoveryProposalEntity proposal = new RecoveryProposalEntity();
        proposal.setProposalId(request.getProposalId());
        proposal.setProposalVersion(1);
        proposal.setActionRequestId("act-" + UUID.randomUUID());
        proposal.setCaseKey(requireText(snapshot.getCaseKey(), "caseKey"));
        proposal.setIdentifierType(request.getIdentifierType());
        proposal.setIdentifierValue(request.getIdentifierValue());
        proposal.setActionType(ACTION_REPLAY);
        proposal.setTargetType(TARGET_DEAD_LETTER);
        proposal.setTargetKey(candidate.getTargetKey());
        proposal.setStateFingerprint(fingerprint(snapshot, candidate.getTargetKey()));
        proposal.setEffectsDigest(digest(effects));
        proposal.setWarningsDigest(digest(warnings));
        proposal.setCanExecute(true);
        proposal.setStatus(PROPOSAL_ACTIVE);
        proposal.setEffectsJson(toJson(effects));
        proposal.setWarningsJson(toJson(warnings));
        proposal.setSuggestedReason(limit(request.getSuggestedReason(), 512));
        proposal.setExpiresAt(now.plusSeconds(proposalTtlSeconds));
        proposal.setPreviewDigest(previewDigest(proposal, effects, warnings));
        proposal.setCreatedAt(now);
        proposal.setUpdatedAt(now);
        try {
            proposalMapper.insert(proposal);
        } catch (DuplicateKeyException exception) {
            RecoveryProposalEntity winner = findByProposalId(request.getProposalId());
            ensureSameCreateRequest(winner, request);
            return toResult(winner, inspect(winner));
        }
        return toResult(proposal, snapshot);
    }

    @Override
    public RecoveryProposalResult find(String proposalId) {
        RecoveryProposalEntity proposal = requireProposal(proposalId);
        proposal = expireIfNecessary(proposal);
        return toResult(proposal, inspect(proposal));
    }

    @Override
    public RecoveryProposalResult execute(RecoveryProposalExecuteRequest request) {
        validateExecute(request);
        RecoveryProposalEntity proposal = requireProposal(request.getProposalId());
        ensureApprovalBindsExactPreview(proposal, request);

        if (Objects.equals(proposal.getStatus(), PROPOSAL_ACTIVE)) {
            if (!LocalDateTime.now(clock).isBefore(proposal.getExpiresAt())) {
                changeStatusIfPreviewed(proposal.getId(), PROPOSAL_EXPIRED);
                throw new BizException("PREVIEW_EXPIRED");
            }
            RecoveryCaseResult current = inspect(proposal);
            String currentFingerprint = fingerprint(current, proposal.getTargetKey());
            if (!Objects.equals(currentFingerprint, proposal.getStateFingerprint())
                    || !isSameEligibleTarget(current, proposal)) {
                changeStatusIfPreviewed(proposal.getId(), PROPOSAL_INVALIDATED);
                throw new BizException("PRECONDITION_CHANGED");
            }
            approve(proposal, request);
            proposal = requireProposal(request.getProposalId());
        } else if (Objects.equals(proposal.getStatus(), PROPOSAL_APPROVED)) {
            ensureSameApproval(proposal, request);
        } else {
            throw new BizException("Proposal 当前状态不可执行：" + proposalStatus(proposal.getStatus()));
        }

        executeApprovedAction(
                proposal,
                request.getApprovedBy(),
                request.getExecutionOwner()
        );
        return find(proposal.getProposalId());
    }

    @Override
    public RecoveryActionResult findAction(String actionRequestId) {
        RecoveryProposalEntity proposal = requireProposalByActionRequestId(actionRequestId);
        RecoveryActionLogEntity action = findActionLog(proposal.getActionRequestId());
        RecoveryCaseResult snapshot = inspect(proposal);
        return toActionResult(proposal, action, snapshot, reconciliationStatus(action, snapshot));
    }

    @Override
    public RecoveryActionResult reconcileAction(
            String actionRequestId,
            RecoveryActionReconcileRequest request
    ) {
        String owner = request == null ? null : normalize(request.getExecutionOwner(), 128);
        if (!StringUtils.hasText(owner)) {
            throw new BizException("reconcile 必须携带 executionOwner");
        }
        RecoveryProposalEntity proposal = requireProposalByActionRequestId(actionRequestId);
        RecoveryActionLogEntity action = findActionLog(proposal.getActionRequestId());
        RecoveryCaseResult snapshot = inspect(proposal);

        if (action == null) {
            return toActionResult(proposal, null, snapshot,
                    isBusinessConverged(snapshot) ? "RESOLVED" : "NOT_STARTED");
        }
        if (Objects.equals(action.getStatus(), ACTION_SUBMITTED)) {
            return toActionResult(proposal, action, snapshot, reconciliationStatus(action, snapshot));
        }
        if (Objects.equals(action.getStatus(), ACTION_FAILED)
                || Objects.equals(action.getStatus(), ACTION_MANUAL_REVIEW)) {
            return toActionResult(proposal, action, snapshot, "MANUAL_REVIEW");
        }
        if (!Objects.equals(action.getStatus(), ACTION_EXECUTING)) {
            return toActionResult(proposal, action, snapshot, "NOT_STARTED");
        }

        if (isBusinessConverged(snapshot)) {
            markReconciledSubmitted(action, owner);
            action = findActionLog(proposal.getActionRequestId());
            return toActionResult(proposal, action, inspect(proposal), "RESOLVED");
        }

        Integer deadStatus = targetDeadLetterStatus(snapshot, proposal.getTargetKey());
        if (Objects.equals(deadStatus, DEAD_REPLAYING)) {
            return toActionResult(proposal, action, snapshot, "WAITING_REPLAY_RESULT");
        }
        if (Objects.equals(deadStatus, DEAD_PENDING) && leaseExpired(action)) {
            executeApprovedAction(proposal, proposal.getApprovedBy(), owner);
            action = findActionLog(proposal.getActionRequestId());
            return toActionResult(proposal, action, inspect(proposal), "RECLAIMED");
        }
        if (Objects.equals(deadStatus, DEAD_PENDING)) {
            return toActionResult(proposal, action, snapshot, "WAITING_ACTIVE_LEASE");
        }

        markManualReview(action, owner, "无法证明恢复动作或业务结果，禁止自动重放");
        action = findActionLog(proposal.getActionRequestId());
        return toActionResult(proposal, action, inspect(proposal), "MANUAL_REVIEW");
    }

    private void executeApprovedAction(RecoveryProposalEntity proposal,
                                       String approvedBy,
                                       String executionOwner) {
        RecoveryDeadLetterRequest action = new RecoveryDeadLetterRequest();
        action.setActionRequestId(proposal.getActionRequestId());
        action.setDeadLetterId(parseTargetId(proposal.getTargetKey()));
        action.setActionType(proposal.getActionType());
        action.setOperator(limit(approvedBy, 64));
        action.setReason(proposal.getSuggestedReason());
        action.setExecutionOwner(StringUtils.hasText(executionOwner)
                ? normalize(executionOwner, 128)
                : "proposal:" + proposal.getProposalId());
        action.setForce(false);
        recoveryService.executeDeadLetter(action);
    }

    private void validateCreate(RecoveryProposalCreateRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getProposalId())
                || !StringUtils.hasText(request.getIdentifierType())
                || !StringUtils.hasText(request.getIdentifierValue())) {
            throw new BizException("Proposal 请求参数不完整");
        }
        request.setProposalId(normalize(request.getProposalId(), 128));
        request.setIdentifierType(normalize(request.getIdentifierType(), 32).toUpperCase());
        request.setIdentifierValue(normalize(request.getIdentifierValue(), 128));
        request.setActionType(StringUtils.hasText(request.getActionType())
                ? normalize(request.getActionType(), 64).toUpperCase()
                : ACTION_REPLAY);
        if (!ACTION_REPLAY.equals(request.getActionType())) {
            throw new BizException("OrderCare V1 Proposal 仅支持 REPLAY");
        }
    }

    private void validateExecute(RecoveryProposalExecuteRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getProposalId())
                || request.getProposalVersion() == null
                || !StringUtils.hasText(request.getStateFingerprint())
                || !StringUtils.hasText(request.getEffectsDigest())
                || !StringUtils.hasText(request.getWarningsDigest())
                || !StringUtils.hasText(request.getPreviewDigest())
                || !StringUtils.hasText(request.getApprovalId())
                || !StringUtils.hasText(request.getApprovedBy())) {
            throw new BizException("execute 必须携带完整的 Proposal 版本和审批绑定证据");
        }
    }

    private RecoveryCaseResult.RecoveryCandidate requireEligibleCandidate(
            RecoveryCaseResult snapshot,
            String actionType
    ) {
        if (snapshot == null
                || !Boolean.TRUE.equals(snapshot.getFound())
                || !Boolean.TRUE.equals(snapshot.getFactsComplete())
                || !Boolean.TRUE.equals(snapshot.getRecoveryEligible())
                || !"REPLAY_CANDIDATE".equals(snapshot.getDiagnosisCode())) {
            throw new BizException("当前案例不满足恢复预演条件："
                    + (snapshot == null ? "NO_CASE" : snapshot.getDiagnosisCode()));
        }
        List<RecoveryCaseResult.RecoveryCandidate> candidates = snapshot.getCandidates().stream()
                .filter(item -> Boolean.TRUE.equals(item.getEligible()))
                .filter(item -> actionType.equals(item.getActionType()))
                .filter(item -> TARGET_DEAD_LETTER.equals(item.getTargetType()))
                .toList();
        if (candidates.size() != 1) {
            throw new BizException("恢复候选不唯一，必须人工核对");
        }
        return candidates.get(0);
    }

    private boolean isSameEligibleTarget(RecoveryCaseResult snapshot, RecoveryProposalEntity proposal) {
        try {
            RecoveryCaseResult.RecoveryCandidate candidate = requireEligibleCandidate(snapshot, proposal.getActionType());
            return Objects.equals(candidate.getTargetType(), proposal.getTargetType())
                    && Objects.equals(candidate.getTargetKey(), proposal.getTargetKey());
        } catch (BizException exception) {
            return false;
        }
    }

    private void ensureApprovalBindsExactPreview(
            RecoveryProposalEntity proposal,
            RecoveryProposalExecuteRequest request
    ) {
        if (!Objects.equals(proposal.getProposalVersion(), request.getProposalVersion())
                || !Objects.equals(proposal.getStateFingerprint(), request.getStateFingerprint())
                || !Objects.equals(proposal.getEffectsDigest(), request.getEffectsDigest())
                || !Objects.equals(proposal.getWarningsDigest(), request.getWarningsDigest())
                || !Objects.equals(proposal.getPreviewDigest(), request.getPreviewDigest())) {
            throw new BizException("APPROVAL_SNAPSHOT_MISMATCH");
        }
    }

    private void approve(RecoveryProposalEntity proposal, RecoveryProposalExecuteRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        int rows = proposalMapper.update(
                null,
                Wrappers.<RecoveryProposalEntity>lambdaUpdate()
                        .eq(RecoveryProposalEntity::getId, proposal.getId())
                        .eq(RecoveryProposalEntity::getStatus, PROPOSAL_ACTIVE)
                        .gt(RecoveryProposalEntity::getExpiresAt, now)
                        .set(RecoveryProposalEntity::getStatus, PROPOSAL_APPROVED)
                        .set(RecoveryProposalEntity::getApprovalId, normalize(request.getApprovalId(), 128))
                        .set(RecoveryProposalEntity::getApprovedBy, normalize(request.getApprovedBy(), 128))
                        .set(RecoveryProposalEntity::getApprovalComment, limit(request.getApprovalComment(), 512))
                        .set(RecoveryProposalEntity::getApprovedAt, now)
                        .set(RecoveryProposalEntity::getUpdatedAt, now)
        );
        if (rows != 1) {
            RecoveryProposalEntity winner = requireProposal(proposal.getProposalId());
            if (!Objects.equals(winner.getStatus(), PROPOSAL_APPROVED)) {
                throw new BizException("Proposal 状态已变化：" + proposalStatus(winner.getStatus()));
            }
            ensureSameApproval(winner, request);
        }
    }

    private void ensureSameApproval(RecoveryProposalEntity proposal, RecoveryProposalExecuteRequest request) {
        if (!Objects.equals(proposal.getApprovalId(), normalize(request.getApprovalId(), 128))
                || !Objects.equals(proposal.getApprovedBy(), normalize(request.getApprovedBy(), 128))) {
            throw new BizException("Proposal 已绑定其他审批记录");
        }
    }

    private RecoveryProposalEntity expireIfNecessary(RecoveryProposalEntity proposal) {
        if (!Objects.equals(proposal.getStatus(), PROPOSAL_ACTIVE)
                || LocalDateTime.now(clock).isBefore(proposal.getExpiresAt())) {
            return proposal;
        }
        changeStatusIfPreviewed(proposal.getId(), PROPOSAL_EXPIRED);
        return requireProposal(proposal.getProposalId());
    }

    private void changeStatusIfPreviewed(Long id, int nextStatus) {
        proposalMapper.update(
                null,
                Wrappers.<RecoveryProposalEntity>lambdaUpdate()
                        .eq(RecoveryProposalEntity::getId, id)
                        .eq(RecoveryProposalEntity::getStatus, PROPOSAL_ACTIVE)
                        .set(RecoveryProposalEntity::getStatus, nextStatus)
                        .set(RecoveryProposalEntity::getUpdatedAt, LocalDateTime.now(clock))
        );
    }

    private RecoveryProposalResult toResult(RecoveryProposalEntity proposal, RecoveryCaseResult snapshot) {
        RecoveryActionLogEntity action = findActionLog(proposal.getActionRequestId());
        String actionStatus = actionStatus(action == null ? null : action.getStatus());
        RecoveryProposalResult result = new RecoveryProposalResult();
        result.setProposalId(proposal.getProposalId());
        result.setProposalVersion(proposal.getProposalVersion());
        result.setProposalStatus(proposalStatus(proposal.getStatus()));
        result.setActionRequestId(proposal.getActionRequestId());
        result.setActionStatus(actionStatus);
        result.setCaseOutcome(caseOutcome(snapshot, actionStatus));
        result.setCaseKey(proposal.getCaseKey());
        result.setIdentifierType(proposal.getIdentifierType());
        result.setIdentifierValue(proposal.getIdentifierValue());
        result.setActionType(proposal.getActionType());
        result.setTargetType(proposal.getTargetType());
        result.setTargetKey(proposal.getTargetKey());
        result.setStateFingerprint(proposal.getStateFingerprint());
        result.setEffectsDigest(proposal.getEffectsDigest());
        result.setWarningsDigest(proposal.getWarningsDigest());
        result.setPreviewDigest(proposal.getPreviewDigest());
        result.setCanExecute(Boolean.TRUE.equals(proposal.getCanExecute())
                && Objects.equals(proposal.getStatus(), PROPOSAL_ACTIVE));
        result.setEffects(readList(proposal.getEffectsJson()));
        result.setWarnings(readList(proposal.getWarningsJson()));
        result.setSuggestedReason(proposal.getSuggestedReason());
        result.setApprovalId(proposal.getApprovalId());
        result.setApprovedBy(proposal.getApprovedBy());
        result.setApprovalComment(proposal.getApprovalComment());
        result.setApprovedAt(proposal.getApprovedAt());
        result.setExpiresAt(proposal.getExpiresAt());
        result.setCreatedAt(proposal.getCreatedAt());
        result.setUpdatedAt(proposal.getUpdatedAt());
        return result;
    }

    private String caseOutcome(RecoveryCaseResult snapshot, String actionStatus) {
        if (snapshot == null) {
            return "MANUAL_REVIEW";
        }
        boolean converged = isBusinessConverged(snapshot);
        if ("SUBMITTED".equals(actionStatus) && converged) {
            return "RESOLVED";
        }
        if (converged) {
            return "ALREADY_CONVERGED";
        }
        if (List.of("FAILED", "MANUAL_REVIEW").contains(actionStatus)
                || List.of("DEPENDENCY_UNAVAILABLE", "FACT_CONFLICT", "UNSUPPORTED_EVENT")
                .contains(snapshot.getDiagnosisCode())) {
            return "MANUAL_REVIEW";
        }
        return "NOT_CONVERGED";
    }

    private boolean isBusinessConverged(RecoveryCaseResult snapshot) {
        return snapshot != null
                && "ALREADY_CONVERGED".equals(snapshot.getDiagnosisCode())
                && snapshot.getDeadLetters().stream().noneMatch(item ->
                Objects.equals(item.getStatus(), DEAD_PENDING)
                        || Objects.equals(item.getStatus(), DEAD_REPLAYING));
    }

    private Integer targetDeadLetterStatus(RecoveryCaseResult snapshot, String targetKey) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.getDeadLetters().stream()
                .filter(item -> Objects.equals(String.valueOf(item.getDeadLetterId()), targetKey))
                .map(RecoveryCaseResult.DeadLetterFact::getStatus)
                .findFirst()
                .orElse(null);
    }

    private boolean leaseExpired(RecoveryActionLogEntity action) {
        return action.getExecutionLeaseUntil() == null
                || !LocalDateTime.now(clock).isBefore(action.getExecutionLeaseUntil());
    }

    private void markReconciledSubmitted(RecoveryActionLogEntity action, String owner) {
        LocalDateTime now = LocalDateTime.now(clock);
        actionLogMapper.update(
                null,
                Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                        .eq(RecoveryActionLogEntity::getId, action.getId())
                        .eq(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                        .set(RecoveryActionLogEntity::getStatus, ACTION_SUBMITTED)
                        .set(RecoveryActionLogEntity::getExecutionOwner, owner)
                        .set(RecoveryActionLogEntity::getExecutionLeaseUntil, null)
                        .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                        .set(RecoveryActionLogEntity::getReconcileCount, reconcileCount(action) + 1)
                        .set(RecoveryActionLogEntity::getReconciledAt, now)
                        .set(RecoveryActionLogEntity::getLastError, null)
                        .set(RecoveryActionLogEntity::getExecuteResult, toJson(Map.of(
                                "status", "RECONCILED_SUBMITTED",
                                "reason", "业务事实已收敛，补记动作提交结果",
                                "reconciledAt", now
                        )))
                        .set(RecoveryActionLogEntity::getUpdatedAt, now)
        );
    }

    private void markManualReview(RecoveryActionLogEntity action, String owner, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        actionLogMapper.update(
                null,
                Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                        .eq(RecoveryActionLogEntity::getId, action.getId())
                        .eq(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                        .set(RecoveryActionLogEntity::getStatus, ACTION_MANUAL_REVIEW)
                        .set(RecoveryActionLogEntity::getExecutionOwner, owner)
                        .set(RecoveryActionLogEntity::getExecutionLeaseUntil, null)
                        .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                        .set(RecoveryActionLogEntity::getReconcileCount, reconcileCount(action) + 1)
                        .set(RecoveryActionLogEntity::getReconciledAt, now)
                        .set(RecoveryActionLogEntity::getLastError, limit(reason, 1024))
                        .set(RecoveryActionLogEntity::getUpdatedAt, now)
        );
    }

    private int reconcileCount(RecoveryActionLogEntity action) {
        return action.getReconcileCount() == null ? 0 : action.getReconcileCount();
    }

    private RecoveryActionResult toActionResult(
            RecoveryProposalEntity proposal,
            RecoveryActionLogEntity action,
            RecoveryCaseResult snapshot,
            String reconciliationStatus
    ) {
        RecoveryActionResult result = new RecoveryActionResult();
        result.setProposalId(proposal.getProposalId());
        result.setActionRequestId(proposal.getActionRequestId());
        result.setActionType(proposal.getActionType());
        result.setTargetType(proposal.getTargetType());
        result.setTargetKey(proposal.getTargetKey());
        String currentActionStatus = actionStatus(action == null ? null : action.getStatus());
        result.setActionStatus(currentActionStatus);
        result.setCaseOutcome(caseOutcome(snapshot, currentActionStatus));
        result.setReconciliationStatus(reconciliationStatus);
        if (action != null) {
            result.setExecutionOwner(action.getExecutionOwner());
            result.setExecutionLeaseUntil(action.getExecutionLeaseUntil());
            result.setLastHeartbeatAt(action.getLastHeartbeatAt());
            result.setLeaseExpired(leaseExpired(action));
            result.setReconcileCount(reconcileCount(action));
            result.setLastError(action.getLastError());
            result.setExecuteResult(action.getExecuteResult());
            result.setReconciledAt(action.getReconciledAt());
            result.setCreatedAt(action.getCreatedAt());
            result.setUpdatedAt(action.getUpdatedAt());
        } else {
            result.setLeaseExpired(false);
            result.setReconcileCount(0);
        }
        return result;
    }

    private String reconciliationStatus(RecoveryActionLogEntity action, RecoveryCaseResult snapshot) {
        if (action == null) return isBusinessConverged(snapshot) ? "RESOLVED" : "NOT_STARTED";
        if (Objects.equals(action.getStatus(), ACTION_SUBMITTED)) {
            return isBusinessConverged(snapshot) ? "RESOLVED" : "WAITING_CONVERGENCE";
        }
        if (Objects.equals(action.getStatus(), ACTION_EXECUTING)) return "WAITING_EXECUTION";
        if (Objects.equals(action.getStatus(), ACTION_FAILED)
                || Objects.equals(action.getStatus(), ACTION_MANUAL_REVIEW)) return "MANUAL_REVIEW";
        return "NOT_STARTED";
    }

    private RecoveryCaseResult inspect(RecoveryProposalEntity proposal) {
        return caseService.inspect(proposal.getIdentifierType(), proposal.getIdentifierValue());
    }

    private String fingerprint(RecoveryCaseResult snapshot, String targetKey) {
        RecoveryCaseResult.DeadLetterFact target = snapshot.getDeadLetters().stream()
                .filter(item -> Objects.equals(String.valueOf(item.getDeadLetterId()), targetKey))
                .findFirst()
                .orElseThrow(() -> new BizException("目标死信不在当前案例中"));
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("deadLetterId", target.getDeadLetterId());
        canonical.put("deadLetterStatus", target.getStatus());
        canonical.put("deadLetterReplayCount", target.getReplayCount());
        canonical.put("deadLetterUpdatedAt", target.getUpdatedAt());
        canonical.put("messageType", target.getMessageType());
        canonical.put("bizKey", target.getBizKey());
        canonical.put("deductNo", snapshot.getDeduct() == null ? null : snapshot.getDeduct().getDeductNo());
        canonical.put("deductStatus", snapshot.getDeduct() == null ? null : snapshot.getDeduct().getStatus());
        canonical.put("deductUpdatedAt", snapshot.getDeduct() == null ? null : snapshot.getDeduct().getUpdatedAt());
        canonical.put("orderStatus", snapshot.getOrder() == null ? null : snapshot.getOrder().getStatus());
        canonical.put("orderEventVersion", snapshot.getReservation() == null
                ? null : snapshot.getReservation().getOrderEventVersion());
        return digest(toJson(canonical));
    }

    private String previewDigest(RecoveryProposalEntity proposal,
                                 List<String> effects,
                                 List<String> warnings) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("proposalId", proposal.getProposalId());
        canonical.put("proposalVersion", proposal.getProposalVersion());
        canonical.put("actionType", proposal.getActionType());
        canonical.put("targetType", proposal.getTargetType());
        canonical.put("targetKey", proposal.getTargetKey());
        canonical.put("stateFingerprint", proposal.getStateFingerprint());
        canonical.put("effects", effects);
        canonical.put("warnings", warnings);
        canonical.put("expiresAt", proposal.getExpiresAt());
        return digest(toJson(canonical));
    }

    private String digest(List<String> values) {
        return digest(toJson(values));
    }

    private String digest(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private RecoveryProposalEntity requireProposal(String proposalId) {
        if (!StringUtils.hasText(proposalId)) {
            throw new BizException("proposalId不能为空");
        }
        RecoveryProposalEntity proposal = proposalMapper.selectOne(
                Wrappers.<RecoveryProposalEntity>lambdaQuery()
                        .eq(RecoveryProposalEntity::getProposalId, proposalId.trim())
                        .last("limit 1")
        );
        if (proposal == null) {
            throw new BizException("Proposal不存在：" + proposalId);
        }
        return proposal;
    }

    private RecoveryProposalEntity findByProposalId(String proposalId) {
        return proposalMapper.selectOne(
                Wrappers.<RecoveryProposalEntity>lambdaQuery()
                        .eq(RecoveryProposalEntity::getProposalId, proposalId)
                        .last("limit 1")
        );
    }

    private RecoveryActionLogEntity findActionLog(String actionRequestId) {
        return actionLogMapper.selectOne(
                Wrappers.<RecoveryActionLogEntity>lambdaQuery()
                        .eq(RecoveryActionLogEntity::getActionRequestId, actionRequestId)
                        .last("limit 1")
        );
    }

    private RecoveryProposalEntity requireProposalByActionRequestId(String actionRequestId) {
        if (!StringUtils.hasText(actionRequestId)) {
            throw new BizException("actionRequestId不能为空");
        }
        RecoveryProposalEntity proposal = proposalMapper.selectOne(
                Wrappers.<RecoveryProposalEntity>lambdaQuery()
                        .eq(RecoveryProposalEntity::getActionRequestId, actionRequestId.trim())
                        .last("limit 1")
        );
        if (proposal == null) {
            throw new BizException("Recovery Action 未绑定 Proposal：" + actionRequestId);
        }
        return proposal;
    }

    private void ensureSameCreateRequest(RecoveryProposalEntity proposal, RecoveryProposalCreateRequest request) {
        if (proposal == null
                || !Objects.equals(proposal.getProposalId(), request.getProposalId())
                || !Objects.equals(proposal.getIdentifierType(), request.getIdentifierType())
                || !Objects.equals(proposal.getIdentifierValue(), request.getIdentifierValue())
                || !Objects.equals(proposal.getActionType(), request.getActionType())) {
            throw new BizException("proposalId 已绑定其他预演请求");
        }
    }

    private String proposalStatus(Integer status) {
        if (Objects.equals(status, PROPOSAL_ACTIVE)) return "ACTIVE";
        if (Objects.equals(status, PROPOSAL_APPROVED)) return "APPROVED";
        if (Objects.equals(status, PROPOSAL_REJECTED)) return "REJECTED";
        if (Objects.equals(status, PROPOSAL_EXPIRED)) return "EXPIRED";
        if (Objects.equals(status, PROPOSAL_INVALIDATED)) return "INVALIDATED";
        return "UNKNOWN";
    }

    private String actionStatus(Integer status) {
        if (status == null) return "NOT_STARTED";
        if (Objects.equals(status, ACTION_PREVIEWED)) return "PREVIEWED";
        if (Objects.equals(status, ACTION_EXECUTING)) return "EXECUTING";
        if (Objects.equals(status, ACTION_SUBMITTED)) return "SUBMITTED";
        if (Objects.equals(status, ACTION_FAILED)) return "FAILED";
        if (Objects.equals(status, ACTION_MANUAL_REVIEW)) return "MANUAL_REVIEW";
        return "UNKNOWN";
    }

    private Long parseTargetId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BizException("Proposal targetKey不是有效死信ID");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("serialize recovery proposal failed", exception);
        }
    }

    private List<String> readList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            return new ArrayList<>(List.of("PROPOSAL_SNAPSHOT_UNREADABLE"));
        }
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(name + "不能为空");
        }
        return value;
    }

    private String normalize(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new BizException("参数长度超过限制：" + maxLength);
        }
        return normalized;
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
