package com.javaup.resource.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaup.client.OrderClient;
import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryExecuteResult;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.RecoveryActionLogMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.service.impl.RecoveryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static com.javaup.constant.OrderMqConstant.ORDER_STATE_DLQ;
import static com.javaup.constant.OrderMqConstant.ORDER_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceImplTest {

    @Mock
    private MqDeadLetterService deadLetterService;

    @Mock
    private RecoveryActionLogMapper actionLogMapper;

    @Mock
    private ReservationRequestService requestService;

    @Mock
    private ReservationRequestMapper requestMapper;

    @Mock
    private StockDeductRecordMapper deductRecordMapper;

    @Mock
    private StockItemMapper stockItemMapper;

    @Mock
    private UserReservationQuotaMapper quotaMapper;

    @Mock
    private MqOutboxMapper outboxMapper;

    @Mock
    private OrderClient orderClient;

    private RecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RecoveryActionLogEntity.class
        );
        service = new RecoveryServiceImpl(
                deadLetterService,
                actionLogMapper,
                requestService,
                requestMapper,
                deductRecordMapper,
                stockItemMapper,
                quotaMapper,
                outboxMapper,
                orderClient,
                new ObjectMapper().registerModule(new JavaTimeModule())
        );
    }

    @Test
    void executeDeadLetterShouldReturnIdempotentSuccessWhenSameActionAlreadySucceeded() {
        long deadLetterId = 1L;
        String actionRequestId = "v10-execute-idem-test";
        String operator = "codex";
        String reason = "verify execute idempotency";
        AtomicReference<RecoveryActionLogEntity> storedLog = new AtomicReference<>();

        when(deadLetterService.findById(deadLetterId)).thenReturn(pendingDeadLetter(deadLetterId));
        when(actionLogMapper.selectOne(any())).thenAnswer(invocation -> storedLog.get());
        when(actionLogMapper.insert(any(RecoveryActionLogEntity.class))).thenAnswer(invocation -> {
            RecoveryActionLogEntity log = invocation.getArgument(0);
            log.setId(100L);
            log.setUpdatedAt(LocalDateTime.now());
            storedLog.set(log);
            return 1;
        });
        when(actionLogMapper.update(any(), any())).thenAnswer(invocation -> {
            RecoveryActionLogEntity log = storedLog.get();
            log.setStatus(20);
            log.setUpdatedAt(LocalDateTime.now());
            return 1;
        });

        RecoveryDeadLetterRequest request = ignoreRequest(
                deadLetterId,
                actionRequestId,
                operator,
                reason
        );

        RecoveryExecuteResult first = service.executeDeadLetter(request);
        RecoveryExecuteResult second = service.executeDeadLetter(ignoreRequest(
                deadLetterId,
                actionRequestId,
                operator,
                reason
        ));

        assertEquals("SUCCEEDED", first.getStatus());
        assertEquals("IDEMPOTENT_SUCCEEDED", second.getStatus());
        assertEquals("actionRequestId already succeeded", second.getMessage());
        verify(deadLetterService, times(1)).findById(deadLetterId);
        verify(deadLetterService, times(1)).ignore(deadLetterId, operator, reason, true);
    }

    private RecoveryDeadLetterRequest ignoreRequest(
            Long deadLetterId,
            String actionRequestId,
            String operator,
            String reason
    ) {
        RecoveryDeadLetterRequest request = new RecoveryDeadLetterRequest();
        request.setDeadLetterId(deadLetterId);
        request.setActionRequestId(actionRequestId);
        request.setActionType("IGNORE");
        request.setOperator(operator);
        request.setReason(reason);
        request.setForce(true);
        return request;
    }

    private MqDeadLetterAdminDto pendingDeadLetter(Long id) {
        MqDeadLetterAdminDto dead = new MqDeadLetterAdminDto();
        dead.setId(id);
        dead.setMessageId("message-idem-test");
        dead.setDeadQueue(ORDER_STATE_DLQ);
        dead.setProducerService("floworder-order-service");
        dead.setMessageType(ORDER_TIMEOUT);
        dead.setBizKey("deduct-idem-test");
        dead.setStatus(0);
        dead.setReplayCount(0);
        return dead;
    }
}
