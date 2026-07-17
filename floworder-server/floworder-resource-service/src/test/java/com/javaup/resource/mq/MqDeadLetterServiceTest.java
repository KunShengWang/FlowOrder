package com.javaup.resource.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.client.OrderMqAdminClient;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mq.service.MqOutboxService;
import com.javaup.resource.mq.service.impl.MqDeadLetterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.RELEASED;
import static com.javaup.resource.enums.StockDeductStatusEnum.SOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqDeadLetterServiceTest {

    @Mock
    private MqOutboxService outboxService;

    @Mock
    private OrderMqAdminClient orderMqAdminClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MqDeadLetterMapper deadLetterMapper;

    @Mock
    private MqOutboxMapper outboxMapper;

    @Mock
    private StockDeductRecordMapper deductRecordMapper;

    private MqDeadLetterServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""),
                MqDeadLetterEntity.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""),
                MqOutboxEntity.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""),
                StockDeductRecordEntity.class
        );
        service = new MqDeadLetterServiceImpl(
                outboxService,
                orderMqAdminClient,
                transactionTemplate,
                deadLetterMapper,
                outboxMapper,
                deductRecordMapper,
                objectMapper
        );
    }

    @Test
    void createDeadLetterShouldPersistAndMoveDeductionToManualReview() throws Exception {
        OrderCreateMessage message = new OrderCreateMessage();
        message.setMessageId("create-message-1");
        message.setEventType(ORDER_CREATE_COMMAND);
        CreateOrderDto data = new CreateOrderDto();
        data.setDeductNo("deduct-1");
        message.setData(data);
        when(deductRecordMapper.update(any(), any())).thenReturn(1);

        service.record(
                ORDER_CREATE_DLQ,
                message.getMessageId(),
                objectMapper.writeValueAsString(message),
                "rejected"
        );

        ArgumentCaptor<MqDeadLetterEntity> captor =
                ArgumentCaptor.forClass(MqDeadLetterEntity.class);
        verify(deadLetterMapper).insert(captor.capture());
        verify(deductRecordMapper).update(any(), any());
        assertEquals("deduct-1", captor.getValue().getBizKey());
        assertEquals(RESOURCE_SERVICE, captor.getValue().getProducerService());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void resultDeadLetterShouldPersistWithoutChangingStockDeduction() throws Exception {
        OrderCreateResultMessage message = new OrderCreateResultMessage();
        message.setMessageId("result-message-1");
        message.setEventType(ORDER_CREATE_SUCCEEDED);
        message.setDeductNo("deduct-2");

        service.record(
                ORDER_RESULT_DLQ,
                message.getMessageId(),
                objectMapper.writeValueAsString(message),
                "rejected"
        );

        ArgumentCaptor<MqDeadLetterEntity> captor =
                ArgumentCaptor.forClass(MqDeadLetterEntity.class);
        verify(deadLetterMapper).insert(captor.capture());
        verifyNoInteractions(deductRecordMapper);
        assertEquals("deduct-2", captor.getValue().getBizKey());
        assertEquals(ORDER_SERVICE, captor.getValue().getProducerService());
        assertEquals(ORDER_CREATE_SUCCEEDED, captor.getValue().getMessageType());
    }

    @Test
    void stateDeadLetterShouldUseDeductNoAsBusinessKey() throws Exception {
        OrderStateChangedMessage message = new OrderStateChangedMessage();
        message.setMessageId("state-message-biz-key");
        message.setEventType(ORDER_TIMEOUT);
        message.setDeductNo("deduct-state-biz-key");

        service.record(
                ORDER_STATE_DLQ,
                message.getMessageId(),
                objectMapper.writeValueAsString(message),
                "rejected"
        );

        ArgumentCaptor<MqDeadLetterEntity> captor =
                ArgumentCaptor.forClass(MqDeadLetterEntity.class);
        verify(deadLetterMapper).insert(captor.capture());
        assertEquals(message.getDeductNo(), captor.getValue().getBizKey());
        assertEquals(ORDER_SERVICE, captor.getValue().getProducerService());
        assertEquals(ORDER_TIMEOUT, captor.getValue().getMessageType());
    }

    @Test
    void malformedCreateMessageShouldRecoverBusinessKeyFromOutbox() {
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageType(ORDER_CREATE_COMMAND);
        outbox.setBizKey("deduct-from-outbox");
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(deductRecordMapper.update(any(), any())).thenReturn(1);

        service.record(
                ORDER_CREATE_DLQ,
                "malformed-create-message",
                "{not-json",
                "rejected"
        );

        ArgumentCaptor<MqDeadLetterEntity> captor =
                ArgumentCaptor.forClass(MqDeadLetterEntity.class);
        verify(deadLetterMapper).insert(captor.capture());
        assertEquals("deduct-from-outbox", captor.getValue().getBizKey());
        assertEquals(ORDER_CREATE_COMMAND, captor.getValue().getMessageType());
        assertNotNull(captor.getValue().getLastError());
        assertTrue(captor.getValue().getLastError().startsWith(
                "Create message cannot be parsed"
        ));
    }

    @Test
    void confirmedStateDeadLetterShouldBeConvergedWhenStockIsSold() throws Exception {
        mockOrderStateDeadLetter(ORDER_CONFIRMED, SOLD.getCode());
        when(deadLetterMapper.update(any(), any())).thenReturn(1);

        service.ignore(1L, "admin", "business converged", false);

        verify(deadLetterMapper).update(any(), any());
    }

    @Test
    void cancelledStateDeadLetterShouldBeConvergedWhenStockIsReleased() throws Exception {
        mockOrderStateDeadLetter(ORDER_CANCELLED, RELEASED.getCode());
        when(deadLetterMapper.update(any(), any())).thenReturn(1);

        service.ignore(1L, "admin", "business converged", false);

        verify(deadLetterMapper).update(any(), any());
    }

    @Test
    void timeoutStateDeadLetterShouldBeConvergedWhenStockIsReleased() throws Exception {
        mockOrderStateDeadLetter(ORDER_TIMEOUT, RELEASED.getCode());
        when(deadLetterMapper.update(any(), any())).thenReturn(1);

        service.ignore(1L, "admin", "business converged", false);

        verify(deadLetterMapper).update(any(), any());
    }

    @Test
    void unknownStateDeadLetterShouldNotBeConvergedWhenStockIsReleased() throws Exception {
        mockOrderStateDeadLetter("UNKNOWN_EVENT", RELEASED.getCode());

        BizException exception = assertThrows(
                BizException.class,
                () -> service.ignore(1L, "admin", "invalid event", false)
        );

        assertEquals("业务状态尚未收敛，不能忽略死信", exception.getMessage());
        verify(deadLetterMapper, never()).update(any(), any());
    }

    private void mockOrderStateDeadLetter(String eventType, int deductStatus) throws Exception {
        OrderStateChangedMessage message = new OrderStateChangedMessage();
        message.setMessageId("state-message-1");
        message.setEventType(eventType);
        message.setDeductNo("deduct-state-1");

        MqDeadLetterEntity deadLetter = new MqDeadLetterEntity();
        deadLetter.setId(1L);
        deadLetter.setDeadQueue(ORDER_STATE_DLQ);
        deadLetter.setBizKey(message.getDeductNo());
        deadLetter.setContent(objectMapper.writeValueAsString(message));
        deadLetter.setStatus(0);

        StockDeductRecordEntity deductRecord = new StockDeductRecordEntity();
        deductRecord.setDeductNo(message.getDeductNo());
        deductRecord.setStatus(deductStatus);

        when(deadLetterMapper.selectById(1L)).thenReturn(deadLetter);
        when(deductRecordMapper.selectOne(any())).thenReturn(deductRecord);
    }
}
