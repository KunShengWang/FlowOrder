package com.javaup.resource.service;

import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderQueryDto;
import com.javaup.resource.dto.RecoveryCaseDiagnosis;
import com.javaup.resource.dto.RecoveryCaseResult;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.RecoveryActionLogMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.service.impl.RecoveryCaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.javaup.constant.OrderMqConstant.ORDER_TIMEOUT;
import static com.javaup.enums.OrderStatusEnum.TIMEOUT;
import static com.javaup.resource.enums.StockDeductStatusEnum.ORDER_CREATED;
import static com.javaup.resource.enums.StockDeductStatusEnum.RELEASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryCaseServiceImplTest {

    @Mock
    private ReservationRequestMapper requestMapper;
    @Mock
    private StockDeductRecordMapper deductRecordMapper;
    @Mock
    private StockItemMapper stockItemMapper;
    @Mock
    private MqDeadLetterMapper deadLetterMapper;
    @Mock
    private RecoveryActionLogMapper actionLogMapper;
    @Mock
    private OrderClient orderClient;

    private RecoveryCaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecoveryCaseServiceImpl(
                requestMapper,
                deductRecordMapper,
                stockItemMapper,
                deadLetterMapper,
                actionLogMapper,
                orderClient
        );
    }

    @Test
    void shouldClassifyAlreadyConverged() {
        stubCase(RELEASED.getCode(), validStock(10, 10, 0, 0),
                List.of(resolvedDeadLetter()), List.of(), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.ALREADY_CONVERGED.name(), result.getDiagnosisCode());
        assertFalse(result.getRecoveryEligible());
        assertTrue(result.getFactsComplete());
    }

    @Test
    void shouldClassifyReplayCandidateAndReturnServerOwnedCandidate() {
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(pendingDeadLetter(1L, ORDER_TIMEOUT)), List.of(), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.REPLAY_CANDIDATE.name(), result.getDiagnosisCode());
        assertTrue(result.getRecoveryEligible());
        assertEquals("replay-dead-letter-1", result.getCandidates().get(0).getCandidateId());
        assertEquals("FLOWORDER", result.getCandidates().get(0).getDecisionOwner());
    }

    @Test
    void shouldTreatLocalOrderStatusLagAsDeadLetterEvidenceInsteadOfConflict() {
        ReservationRequestEntity staleReservation = reservation();
        staleReservation.setOrderStatus(10);
        when(requestMapper.selectOne(any())).thenReturn(staleReservation);
        when(deductRecordMapper.selectOne(any())).thenReturn(deduct(ORDER_CREATED.getCode()));
        when(stockItemMapper.selectById(100L)).thenReturn(validStock(10, 8, 2, 0));
        when(deadLetterMapper.selectList(any())).thenReturn(List.of(pendingDeadLetter(1L, ORDER_TIMEOUT)));
        when(actionLogMapper.selectList(any())).thenReturn(List.of());
        when(orderClient.queryByRequestId("request-1")).thenReturn(successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.REPLAY_CANDIDATE.name(), result.getDiagnosisCode());
        assertTrue(result.getEvidence().contains("ORDER_STATUS_GAP_EXPLAINED_BY_DEAD_LETTER"));
        assertFalse(result.getHardRisks().contains("ORDER_STATUS_CONFLICT"));
    }

    @Test
    void shouldClassifySubmittedActionAsInProgress() {
        RecoveryActionLogEntity action = new RecoveryActionLogEntity();
        action.setId(11L);
        action.setStatus(20);
        action.setTargetType("DEAD_LETTER");
        action.setTargetKey("1");
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(pendingDeadLetter(1L, ORDER_TIMEOUT)), List.of(action), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.ACTION_IN_PROGRESS.name(), result.getDiagnosisCode());
        assertTrue(result.getHardRisks().contains("RECOVERY_ACTION_IN_PROGRESS"));
    }

    @Test
    void shouldClassifyOrderServiceFailureAsDependencyUnavailable() {
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(pendingDeadLetter(1L, ORDER_TIMEOUT)), List.of(), null);
        when(orderClient.queryByRequestId("request-1")).thenThrow(new IllegalStateException("timeout"));

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.DEPENDENCY_UNAVAILABLE.name(), result.getDiagnosisCode());
        assertTrue(result.getHardRisks().contains("ORDER_DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void shouldClassifyBrokenInventoryInvariantAsFactConflict() {
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 1, 0),
                List.of(pendingDeadLetter(1L, ORDER_TIMEOUT)), List.of(), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.FACT_CONFLICT.name(), result.getDiagnosisCode());
        assertTrue(result.getHardRisks().contains("INVENTORY_INVARIANT_BROKEN"));
    }

    @Test
    void shouldClassifyUnknownPendingMessageAsUnsupportedEvent() {
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(pendingDeadLetter(1L, "FUTURE_ORDER_EVENT")), List.of(), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.UNSUPPORTED_EVENT.name(), result.getDiagnosisCode());
        assertTrue(result.getHardRisks().contains("UNSUPPORTED_DEAD_LETTER_EVENT"));
    }

    @Test
    void shouldClassifyMissingDeadLetterAsNoRecoveryEvidence() {
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(), List.of(), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(RecoveryCaseDiagnosis.NO_RECOVERY_EVIDENCE.name(), result.getDiagnosisCode());
        assertTrue(result.getHardRisks().contains("NO_RELATED_DEAD_LETTER"));
    }

    @Test
    void shouldBoundUntrustedDiagnosticTextInAgentContract() {
        MqDeadLetterEntity deadLetter = pendingDeadLetter(1L, ORDER_TIMEOUT);
        deadLetter.setDeathReason("d".repeat(600));
        deadLetter.setLastError("e".repeat(600));
        RecoveryActionLogEntity action = new RecoveryActionLogEntity();
        action.setId(11L);
        action.setStatus(30);
        action.setTargetType("DEAD_LETTER");
        action.setTargetKey("1");
        action.setLastError("a".repeat(600));
        stubCase(ORDER_CREATED.getCode(), validStock(10, 8, 2, 0),
                List.of(deadLetter), List.of(action), successfulOrder());

        RecoveryCaseResult result = service.inspect("REQUEST_ID", "request-1");

        assertEquals(512, result.getDeadLetters().get(0).getDeathReason().length());
        assertEquals(512, result.getDeadLetters().get(0).getLastError().length());
        assertEquals(512, result.getRecoveryActions().get(0).getLastError().length());
    }

    private void stubCase(int deductStatus,
                          StockItemEntity stock,
                          List<MqDeadLetterEntity> deadLetters,
                          List<RecoveryActionLogEntity> actions,
                          ApiResponse<OrderQueryDto> orderResponse) {
        when(requestMapper.selectOne(any())).thenReturn(reservation());
        when(deductRecordMapper.selectOne(any())).thenReturn(deduct(deductStatus));
        when(stockItemMapper.selectById(100L)).thenReturn(stock);
        when(deadLetterMapper.selectList(any())).thenReturn(deadLetters);
        if (!deadLetters.isEmpty()) {
            when(actionLogMapper.selectList(any())).thenReturn(actions);
        }
        if (orderResponse != null) {
            when(orderClient.queryByRequestId("request-1")).thenReturn(orderResponse);
        }
    }

    private ReservationRequestEntity reservation() {
        ReservationRequestEntity entity = new ReservationRequestEntity();
        entity.setId(1L);
        entity.setRequestId("request-1");
        entity.setOrderNo("order-1");
        entity.setStatus(20);
        entity.setOrderStatus(TIMEOUT.getCode());
        entity.setStockItemId(100L);
        entity.setLatestOrderEventType(ORDER_TIMEOUT);
        return entity;
    }

    private StockDeductRecordEntity deduct(int status) {
        StockDeductRecordEntity entity = new StockDeductRecordEntity();
        entity.setId(2L);
        entity.setRequestId("request-1");
        entity.setOrderNo("order-1");
        entity.setDeductNo("deduct-1");
        entity.setStockItemId(100L);
        entity.setQuantity(2);
        entity.setStatus(status);
        return entity;
    }

    private StockItemEntity validStock(int total, int available, int locked, int sold) {
        StockItemEntity entity = new StockItemEntity();
        entity.setId(100L);
        entity.setTotalStock(total);
        entity.setAvailableStock(available);
        entity.setLockedStock(locked);
        entity.setSoldStock(sold);
        entity.setVersion(1);
        return entity;
    }

    private MqDeadLetterEntity pendingDeadLetter(long id, String messageType) {
        MqDeadLetterEntity entity = new MqDeadLetterEntity();
        entity.setId(id);
        entity.setMessageId("message-" + id);
        entity.setBizKey("deduct-1");
        entity.setMessageType(messageType);
        entity.setStatus(0);
        entity.setReplayCount(0);
        return entity;
    }

    private MqDeadLetterEntity resolvedDeadLetter() {
        MqDeadLetterEntity entity = pendingDeadLetter(1L, ORDER_TIMEOUT);
        entity.setStatus(20);
        return entity;
    }

    private ApiResponse<OrderQueryDto> successfulOrder() {
        OrderQueryDto order = new OrderQueryDto();
        order.setExists(true);
        order.setOrderNo("order-1");
        order.setStatus(TIMEOUT.getCode());
        return ApiResponse.success(order);
    }
}
