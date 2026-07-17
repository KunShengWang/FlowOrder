package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaup.client.OrderClient;
import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.exception.BizException;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.mapper.*;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.service.ReservationRequestService;
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
class RecoveryActionLeaseServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @Mock private MqDeadLetterService deadLetterService;
    @Mock private RecoveryActionLogMapper actionLogMapper;
    @Mock private ReservationRequestService requestService;
    @Mock private ReservationRequestMapper requestMapper;
    @Mock private StockDeductRecordMapper deductRecordMapper;
    @Mock private StockItemMapper stockItemMapper;
    @Mock private UserReservationQuotaMapper quotaMapper;
    @Mock private MqOutboxMapper outboxMapper;
    @Mock private OrderClient orderClient;

    private RecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryActionLogEntity.class
        );
        service = new RecoveryServiceImpl(
                deadLetterService, actionLogMapper, requestService, requestMapper,
                deductRecordMapper, stockItemMapper, quotaMapper, outboxMapper,
                orderClient, new ObjectMapper().registerModule(new JavaTimeModule()),
                15, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void activeExecutingLeaseMustBlockDuplicateReplay() {
        RecoveryActionLogEntity action = executingAction(localNow().plusSeconds(10));
        when(actionLogMapper.selectOne(any())).thenReturn(action);
        when(deadLetterService.findById(9L)).thenReturn(pendingDeadLetter());

        BizException error = assertThrows(BizException.class,
                () -> service.executeDeadLetter(request("worker-b")));

        assertEquals("ACTION_EXECUTING_LEASE_ACTIVE", error.getMessage());
        verify(deadLetterService, never()).replay(anyLong(), anyString());
        verify(actionLogMapper, never()).insert(any(RecoveryActionLogEntity.class));
    }

    @Test
    void expiredExecutingLeaseMustReuseSameActionRequestId() {
        RecoveryActionLogEntity action = executingAction(localNow().minusSeconds(1));
        AtomicReference<Integer> updates = new AtomicReference<>(0);
        when(actionLogMapper.selectOne(any())).thenReturn(action);
        when(actionLogMapper.update(isNull(), any())).thenAnswer(invocation -> {
            updates.set(updates.get() + 1);
            return 1;
        });
        when(deadLetterService.findById(9L)).thenReturn(pendingDeadLetter());

        var result = service.executeDeadLetter(request("worker-b"));

        assertEquals("SUBMITTED", result.getStatus());
        assertEquals("act-m3-1", result.getActionRequestId());
        assertEquals("worker-b", action.getExecutionOwner());
        assertEquals(1, action.getReconcileCount());
        assertTrue(updates.get() >= 2);
        verify(deadLetterService, times(1)).replay(9L, "reviewer-a");
        verify(actionLogMapper, never()).insert(any(RecoveryActionLogEntity.class));
    }

    @Test
    void staleOwnerMustNotPublishACompletionAfterLeaseWasTakenAgain() {
        RecoveryActionLogEntity action = executingAction(localNow().minusSeconds(1));
        AtomicReference<Integer> updates = new AtomicReference<>(0);
        when(actionLogMapper.selectOne(any())).thenReturn(action);
        when(actionLogMapper.update(isNull(), any())).thenAnswer(invocation -> {
            int call = updates.updateAndGet(value -> value + 1);
            return call == 1 ? 1 : 0;
        });
        when(deadLetterService.findById(9L)).thenReturn(pendingDeadLetter());

        BizException error = assertThrows(BizException.class,
                () -> service.executeDeadLetter(request("worker-b")));

        assertEquals("ACTION_EXECUTION_LEASE_LOST", error.getMessage());
        verify(deadLetterService, times(1)).replay(9L, "reviewer-a");
        assertTrue(updates.get() >= 2);
    }

    private RecoveryDeadLetterRequest request(String owner) {
        RecoveryDeadLetterRequest request = new RecoveryDeadLetterRequest();
        request.setActionRequestId("act-m3-1");
        request.setDeadLetterId(9L);
        request.setActionType("REPLAY");
        request.setOperator("reviewer-a");
        request.setReason("M3 lease test");
        request.setExecutionOwner(owner);
        request.setForce(false);
        return request;
    }

    private RecoveryActionLogEntity executingAction(LocalDateTime leaseUntil) {
        RecoveryActionLogEntity action = new RecoveryActionLogEntity();
        action.setId(10L);
        action.setActionRequestId("act-m3-1");
        action.setActionType("REPLAY");
        action.setTargetType("DEAD_LETTER");
        action.setTargetKey("9");
        action.setStatus(10);
        action.setExecutionOwner("worker-a");
        action.setExecutionLeaseUntil(leaseUntil);
        action.setReconcileCount(0);
        return action;
    }

    private MqDeadLetterAdminDto pendingDeadLetter() {
        MqDeadLetterAdminDto dead = new MqDeadLetterAdminDto();
        dead.setId(9L);
        dead.setStatus(0);
        return dead;
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
