package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaup.exception.BizException;
import com.javaup.resource.dto.RecoveryCaseResult;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryExecuteResult;
import com.javaup.resource.dto.RecoveryProposalCreateRequest;
import com.javaup.resource.dto.RecoveryProposalExecuteRequest;
import com.javaup.resource.dto.RecoveryProposalResult;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.entity.RecoveryProposalEntity;
import com.javaup.resource.mapper.RecoveryActionLogMapper;
import com.javaup.resource.mapper.RecoveryProposalMapper;
import com.javaup.resource.service.RecoveryCaseService;
import com.javaup.resource.service.RecoveryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryProposalServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Mock
    private RecoveryProposalMapper proposalMapper;

    @Mock
    private RecoveryActionLogMapper actionLogMapper;

    @Mock
    private RecoveryCaseService caseService;

    @Mock
    private RecoveryService recoveryService;

    private final AtomicReference<RecoveryProposalEntity> storedProposal = new AtomicReference<>();
    private final AtomicReference<RecoveryActionLogEntity> storedAction = new AtomicReference<>();
    private RecoveryProposalServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryProposalEntity.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryActionLogEntity.class
        );
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new RecoveryProposalServiceImpl(
                proposalMapper,
                actionLogMapper,
                caseService,
                recoveryService,
                objectMapper,
                600,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(proposalMapper.selectOne(any())).thenAnswer(ignored -> storedProposal.get());
        lenient().when(actionLogMapper.selectOne(any())).thenAnswer(ignored -> storedAction.get());
        lenient().when(proposalMapper.insert(any(RecoveryProposalEntity.class))).thenAnswer(invocation -> {
            RecoveryProposalEntity proposal = invocation.getArgument(0);
            proposal.setId(100L);
            storedProposal.set(proposal);
            return 1;
        });
    }

    @Test
    void createShouldSeparateProposalAndActionIdsAndPersistImmutableSnapshot() {
        when(caseService.inspect("REQUEST_ID", "req-1")).thenReturn(replayCandidate("req-1", 11L, 0));

        RecoveryProposalResult result = service.create(createRequest("preview-call-1", "req-1"));

        assertEquals("prop-preview-call-1", result.getProposalId());
        assertTrue(result.getActionRequestId().startsWith("act-"));
        assertNotEquals(result.getProposalId(), result.getActionRequestId());
        assertEquals("ACTIVE", result.getProposalStatus());
        assertEquals("NOT_STARTED", result.getActionStatus());
        assertEquals("NOT_CONVERGED", result.getCaseOutcome());
        assertTrue(result.getCanExecute());
        assertEquals(64, result.getStateFingerprint().length());
        assertEquals(64, result.getEffectsDigest().length());
        assertEquals(64, result.getWarningsDigest().length());
        assertEquals(64, result.getPreviewDigest().length());
        assertEquals(3, result.getEffects().size());
        assertEquals(3, result.getWarnings().size());
        assertEquals("Agent suggests replay after diagnosis", result.getSuggestedReason());
    }

    @Test
    void createShouldReturnSameProposalForSameProposalId() {
        when(caseService.inspect("REQUEST_ID", "req-2")).thenReturn(replayCandidate("req-2", 12L, 0));
        RecoveryProposalCreateRequest request = createRequest("preview-call-2", "req-2");

        RecoveryProposalResult first = service.create(request);
        RecoveryProposalResult second = service.create(createRequest("preview-call-2", "req-2"));

        assertEquals(first.getProposalId(), second.getProposalId());
        assertEquals(first.getActionRequestId(), second.getActionRequestId());
        verify(caseService, times(2)).inspect("REQUEST_ID", "req-2");
        verify(proposalMapper, times(1)).insert(any(RecoveryProposalEntity.class));
    }

    @Test
    void executeShouldBindApprovalAndKeepThreeStatusDimensionsSeparate() {
        RecoveryCaseResult candidate = replayCandidate("req-3", 13L, 0);
        RecoveryCaseResult resolved = converged("req-3", 13L, 20);
        when(caseService.inspect("REQUEST_ID", "req-3"))
                .thenReturn(candidate, candidate, resolved);
        RecoveryProposalResult preview = service.create(createRequest("preview-call-3", "req-3"));
        RecoveryProposalExecuteRequest execute = approvedRequest(preview, "approval-3", "operator-3");
        when(proposalMapper.update(isNull(), any())).thenAnswer(invocation -> {
            RecoveryProposalEntity proposal = storedProposal.get();
            proposal.setStatus(10);
            proposal.setApprovalId("approval-3");
            proposal.setApprovedBy("operator-3");
            proposal.setApprovalComment("确认影响与警告");
            proposal.setApprovedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            return 1;
        });
        when(recoveryService.executeDeadLetter(any(RecoveryDeadLetterRequest.class))).thenAnswer(invocation -> {
            RecoveryActionLogEntity action = new RecoveryActionLogEntity();
            action.setActionRequestId(preview.getActionRequestId());
            action.setStatus(20);
            storedAction.set(action);
            RecoveryExecuteResult result = new RecoveryExecuteResult();
            result.setStatus("SUBMITTED");
            return result;
        });

        RecoveryProposalResult result = service.execute(execute);

        assertEquals("APPROVED", result.getProposalStatus());
        assertEquals("SUBMITTED", result.getActionStatus());
        assertEquals("RESOLVED", result.getCaseOutcome());
        assertEquals("approval-3", result.getApprovalId());
        assertEquals("operator-3", result.getApprovedBy());
        assertEquals("确认影响与警告", result.getApprovalComment());
        assertFalse(result.getCanExecute());
        verify(recoveryService).executeDeadLetter(any(RecoveryDeadLetterRequest.class));
    }

    @Test
    void executeShouldRejectApprovalSnapshotMismatchBeforeBusinessCall() {
        when(caseService.inspect("REQUEST_ID", "req-4")).thenReturn(replayCandidate("req-4", 14L, 0));
        RecoveryProposalResult preview = service.create(createRequest("preview-call-4", "req-4"));
        RecoveryProposalExecuteRequest execute = approvedRequest(preview, "approval-4", "operator-4");
        execute.setWarningsDigest("tampered");

        BizException exception = assertThrows(BizException.class, () -> service.execute(execute));

        assertTrue(exception.getMessage().contains("APPROVAL_SNAPSHOT_MISMATCH"));
        verify(recoveryService, never()).executeDeadLetter(any());
    }

    @Test
    void executeShouldInvalidateProposalWhenBusinessStateDrifts() {
        RecoveryCaseResult original = replayCandidate("req-5", 15L, 0);
        RecoveryCaseResult drifted = replayCandidate("req-5", 15L, 1);
        when(caseService.inspect("REQUEST_ID", "req-5")).thenReturn(original, drifted);
        RecoveryProposalResult preview = service.create(createRequest("preview-call-5", "req-5"));
        when(proposalMapper.update(isNull(), any())).thenAnswer(invocation -> {
            storedProposal.get().setStatus(40);
            return 1;
        });

        BizException exception = assertThrows(BizException.class, () -> service.execute(
                approvedRequest(preview, "approval-5", "operator-5")
        ));

        assertTrue(exception.getMessage().contains("PRECONDITION_CHANGED"));
        assertEquals(40, storedProposal.get().getStatus());
        verify(recoveryService, never()).executeDeadLetter(any());
    }

    @Test
    void executeShouldExpireProposalAndNeverReuseOldApproval() {
        when(caseService.inspect("REQUEST_ID", "req-6")).thenReturn(replayCandidate("req-6", 16L, 0));
        RecoveryProposalResult preview = service.create(createRequest("preview-call-6", "req-6"));
        storedProposal.get().setExpiresAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(proposalMapper.update(isNull(), any())).thenAnswer(invocation -> {
            storedProposal.get().setStatus(30);
            return 1;
        });

        BizException exception = assertThrows(BizException.class, () -> service.execute(
                approvedRequest(preview, "approval-6", "operator-6")
        ));

        assertTrue(exception.getMessage().contains("PREVIEW_EXPIRED"));
        assertEquals(30, storedProposal.get().getStatus());
        verify(recoveryService, never()).executeDeadLetter(any());
    }

    @Test
    void outcomeShouldRequireRelatedDeadLettersToBeTerminal() {
        RecoveryCaseResult candidate = replayCandidate("req-7", 17L, 0);
        RecoveryCaseResult businessConvergedButDeadLetterPending = converged("req-7", 17L, 0);
        when(caseService.inspect("REQUEST_ID", "req-7"))
                .thenReturn(candidate, candidate, businessConvergedButDeadLetterPending);
        RecoveryProposalResult preview = service.create(createRequest("preview-call-7", "req-7"));
        when(proposalMapper.update(isNull(), any())).thenAnswer(invocation -> {
            storedProposal.get().setStatus(10);
            storedProposal.get().setApprovalId("approval-7");
            storedProposal.get().setApprovedBy("operator-7");
            return 1;
        });
        when(recoveryService.executeDeadLetter(any())).thenAnswer(invocation -> {
            RecoveryActionLogEntity action = new RecoveryActionLogEntity();
            action.setActionRequestId(preview.getActionRequestId());
            action.setStatus(20);
            storedAction.set(action);
            return new RecoveryExecuteResult();
        });

        RecoveryProposalResult result = service.execute(
                approvedRequest(preview, "approval-7", "operator-7")
        );

        assertEquals("SUBMITTED", result.getActionStatus());
        assertEquals("NOT_CONVERGED", result.getCaseOutcome());
    }

    @Test
    void createShouldRejectCasesWithoutOneServerOwnedEligibleCandidate() {
        RecoveryCaseResult conflict = replayCandidate("req-8", 18L, 0);
        conflict.setDiagnosisCode("FACT_CONFLICT");
        conflict.setRecoveryEligible(false);
        conflict.setCandidates(List.of());
        when(caseService.inspect("REQUEST_ID", "req-8")).thenReturn(conflict);

        BizException exception = assertThrows(BizException.class, () -> service.create(
                createRequest("preview-call-8", "req-8")
        ));

        assertNotNull(exception.getMessage());
        verify(proposalMapper, never()).insert(any(RecoveryProposalEntity.class));
    }

    private RecoveryProposalCreateRequest createRequest(String proposalIdSuffix, String requestId) {
        RecoveryProposalCreateRequest request = new RecoveryProposalCreateRequest();
        request.setProposalId("prop-" + proposalIdSuffix);
        request.setIdentifierType("REQUEST_ID");
        request.setIdentifierValue(requestId);
        request.setActionType("REPLAY");
        request.setSuggestedReason("Agent suggests replay after diagnosis");
        return request;
    }

    private RecoveryProposalExecuteRequest approvedRequest(
            RecoveryProposalResult preview,
            String approvalId,
            String approvedBy
    ) {
        RecoveryProposalExecuteRequest request = new RecoveryProposalExecuteRequest();
        request.setProposalId(preview.getProposalId());
        request.setProposalVersion(preview.getProposalVersion());
        request.setStateFingerprint(preview.getStateFingerprint());
        request.setEffectsDigest(preview.getEffectsDigest());
        request.setWarningsDigest(preview.getWarningsDigest());
        request.setPreviewDigest(preview.getPreviewDigest());
        request.setApprovalId(approvalId);
        request.setApprovedBy(approvedBy);
        request.setApprovalComment("确认影响与警告");
        return request;
    }

    private RecoveryCaseResult replayCandidate(String requestId, Long deadLetterId, int replayCount) {
        RecoveryCaseResult result = baseCase(requestId);
        result.setDiagnosisCode("REPLAY_CANDIDATE");
        result.setRecoveryEligible(true);
        RecoveryCaseResult.DeadLetterFact dead = new RecoveryCaseResult.DeadLetterFact();
        dead.setDeadLetterId(deadLetterId);
        dead.setMessageId("message-" + deadLetterId);
        dead.setMessageType("ORDER_TIMEOUT");
        dead.setBizKey("deduct-" + requestId);
        dead.setStatus(0);
        dead.setReplayCount(replayCount);
        result.setDeadLetters(List.of(dead));
        RecoveryCaseResult.RecoveryCandidate candidate = new RecoveryCaseResult.RecoveryCandidate();
        candidate.setCandidateId("replay-dead-letter-" + deadLetterId);
        candidate.setActionType("REPLAY");
        candidate.setTargetType("DEAD_LETTER");
        candidate.setTargetKey(String.valueOf(deadLetterId));
        candidate.setEligible(true);
        candidate.setDecisionOwner("FLOWORDER");
        result.setCandidates(List.of(candidate));
        return result;
    }

    private RecoveryCaseResult converged(String requestId, Long deadLetterId, int deadStatus) {
        RecoveryCaseResult result = baseCase(requestId);
        result.setDiagnosisCode("ALREADY_CONVERGED");
        result.setRecoveryEligible(false);
        RecoveryCaseResult.DeadLetterFact dead = new RecoveryCaseResult.DeadLetterFact();
        dead.setDeadLetterId(deadLetterId);
        dead.setStatus(deadStatus);
        dead.setReplayCount(1);
        result.setDeadLetters(List.of(dead));
        return result;
    }

    private RecoveryCaseResult baseCase(String requestId) {
        RecoveryCaseResult result = new RecoveryCaseResult();
        result.setCaseKey("request:" + requestId);
        result.setIdentifierType("REQUEST_ID");
        result.setIdentifierValue(requestId);
        result.setCanonicalRequestId(requestId);
        result.setFound(true);
        result.setFactsComplete(true);
        RecoveryCaseResult.ReservationFact reservation = new RecoveryCaseResult.ReservationFact();
        reservation.setRequestId(requestId);
        reservation.setOrderStatus(40);
        result.setReservation(reservation);
        RecoveryCaseResult.OrderFact order = new RecoveryCaseResult.OrderFact();
        order.setDependencyAvailable(true);
        order.setExists(true);
        order.setStatus(40);
        result.setOrder(order);
        RecoveryCaseResult.DeductFact deduct = new RecoveryCaseResult.DeductFact();
        deduct.setDeductNo("deduct-" + requestId);
        deduct.setStatus(20);
        result.setDeduct(deduct);
        RecoveryCaseResult.InventoryFact inventory = new RecoveryCaseResult.InventoryFact();
        inventory.setStockItemId(1L);
        inventory.setTotalStock(100);
        inventory.setAvailableStock(99);
        inventory.setLockedStock(1);
        inventory.setSoldStock(0);
        inventory.setInvariantOk(true);
        inventory.setVersion(1);
        result.setInventory(inventory);
        return result;
    }
}
