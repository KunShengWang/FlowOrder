package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaup.resource.dto.RecoveryActionReconcileRequest;
import com.javaup.resource.dto.RecoveryActionResult;
import com.javaup.resource.dto.RecoveryCaseResult;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryActionReconciliationServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");

    @Mock private RecoveryProposalMapper proposalMapper;
    @Mock private RecoveryActionLogMapper actionLogMapper;
    @Mock private RecoveryCaseService caseService;
    @Mock private RecoveryService recoveryService;

    private RecoveryProposalServiceImpl service;
    private RecoveryProposalEntity proposal;
    private AtomicReference<RecoveryActionLogEntity> action;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryProposalEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryActionLogEntity.class);
        service = new RecoveryProposalServiceImpl(
                proposalMapper, actionLogMapper, caseService, recoveryService,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                600, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        proposal = proposal();
        action = new AtomicReference<>();
        when(proposalMapper.selectOne(any())).thenReturn(proposal);
        when(actionLogMapper.selectOne(any())).thenAnswer(invocation -> action.get());
    }

    @Test
    void responseLostAfterSubmissionMustBeResolvedByQueryOnly() {
        action.set(action(20, null));
        when(caseService.inspect(anyString(), anyString())).thenReturn(caseSnapshot(20, "ALREADY_CONVERGED"));

        RecoveryActionResult result = service.reconcileAction("act-m3-1", reconcile("tool-exec-1"));

        assertEquals("SUBMITTED", result.getActionStatus());
        assertEquals("RESOLVED", result.getCaseOutcome());
        assertEquals("RESOLVED", result.getReconciliationStatus());
        verify(recoveryService, never()).executeDeadLetter(any());
        verify(actionLogMapper, never()).update(any(), any());
    }

    @Test
    void convergedBusinessMustRepairExpiredExecutingLogWithoutReplay() {
        action.set(action(10, localNow().minusSeconds(1)));
        when(caseService.inspect(anyString(), anyString())).thenReturn(caseSnapshot(20, "ALREADY_CONVERGED"));
        when(actionLogMapper.update(isNull(), any())).thenAnswer(invocation -> {
            action.get().setStatus(20);
            action.get().setExecutionLeaseUntil(null);
            action.get().setReconciledAt(localNow());
            return 1;
        });

        RecoveryActionResult result = service.reconcileAction("act-m3-1", reconcile("tool-exec-2"));

        assertEquals("SUBMITTED", result.getActionStatus());
        assertEquals("RESOLVED", result.getCaseOutcome());
        verify(recoveryService, never()).executeDeadLetter(any());
        verify(actionLogMapper, times(1)).update(isNull(), any());
    }

    @Test
    void expiredLeaseWithPendingDeadLetterMustReclaimOriginalAction() {
        action.set(action(10, localNow().minusSeconds(1)));
        when(caseService.inspect(anyString(), anyString())).thenReturn(caseSnapshot(0, "REPLAY_CANDIDATE"));
        doAnswer(invocation -> {
            var command = invocation.<com.javaup.resource.dto.RecoveryDeadLetterRequest>getArgument(0);
            assertEquals("act-m3-1", command.getActionRequestId());
            assertEquals("tool-exec-3", command.getExecutionOwner());
            action.get().setStatus(20);
            return null;
        }).when(recoveryService).executeDeadLetter(any());

        RecoveryActionResult result = service.reconcileAction("act-m3-1", reconcile("tool-exec-3"));

        assertEquals("SUBMITTED", result.getActionStatus());
        assertEquals("RECLAIMED", result.getReconciliationStatus());
        verify(recoveryService, times(1)).executeDeadLetter(any());
    }

    @Test
    void activeLeaseMustOnlyWait() {
        action.set(action(10, localNow().plusSeconds(10)));
        when(caseService.inspect(anyString(), anyString())).thenReturn(caseSnapshot(0, "REPLAY_CANDIDATE"));

        RecoveryActionResult result = service.reconcileAction("act-m3-1", reconcile("tool-exec-4"));

        assertEquals("EXECUTING", result.getActionStatus());
        assertEquals("WAITING_ACTIVE_LEASE", result.getReconciliationStatus());
        verify(recoveryService, never()).executeDeadLetter(any());
    }

    @Test
    void missingActionMustRemainNotStartedWithoutInventingSecondId() {
        when(caseService.inspect(anyString(), anyString())).thenReturn(caseSnapshot(0, "REPLAY_CANDIDATE"));

        RecoveryActionResult result = service.reconcileAction("act-m3-1", reconcile("tool-exec-5"));

        assertEquals("NOT_STARTED", result.getActionStatus());
        assertEquals("NOT_STARTED", result.getReconciliationStatus());
        assertEquals("act-m3-1", result.getActionRequestId());
        verify(recoveryService, never()).executeDeadLetter(any());
    }

    private RecoveryActionReconcileRequest reconcile(String owner) {
        RecoveryActionReconcileRequest request = new RecoveryActionReconcileRequest();
        request.setExecutionOwner(owner);
        return request;
    }

    private RecoveryProposalEntity proposal() {
        RecoveryProposalEntity value = new RecoveryProposalEntity();
        value.setId(1L);
        value.setProposalId("prop-m3-1");
        value.setActionRequestId("act-m3-1");
        value.setIdentifierType("REQUEST_ID");
        value.setIdentifierValue("request-m3-1");
        value.setActionType("REPLAY");
        value.setTargetType("DEAD_LETTER");
        value.setTargetKey("9");
        value.setStatus(10);
        value.setApprovedBy("reviewer-a");
        value.setSuggestedReason("M3 reconciliation test");
        return value;
    }

    private RecoveryActionLogEntity action(int status, LocalDateTime leaseUntil) {
        RecoveryActionLogEntity value = new RecoveryActionLogEntity();
        value.setId(2L);
        value.setActionRequestId("act-m3-1");
        value.setActionType("REPLAY");
        value.setTargetType("DEAD_LETTER");
        value.setTargetKey("9");
        value.setStatus(status);
        value.setExecutionOwner("worker-old");
        value.setExecutionLeaseUntil(leaseUntil);
        value.setReconcileCount(0);
        value.setCreatedAt(localNow().minusMinutes(1));
        value.setUpdatedAt(localNow());
        return value;
    }

    private RecoveryCaseResult caseSnapshot(int deadStatus, String diagnosis) {
        RecoveryCaseResult snapshot = new RecoveryCaseResult();
        snapshot.setFound(true);
        snapshot.setDiagnosisCode(diagnosis);
        RecoveryCaseResult.DeadLetterFact dead = new RecoveryCaseResult.DeadLetterFact();
        dead.setDeadLetterId(9L);
        dead.setStatus(deadStatus);
        snapshot.getDeadLetters().add(dead);
        return snapshot;
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
