package com.javaup.resource.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.client.OrderMqAdminClient;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mq.service.MqOutboxService;
import com.javaup.resource.mq.service.impl.MqDeadLetterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static com.javaup.constant.OrderMqConstant.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        verify(deadLetterMapper).insert(any(MqDeadLetterEntity.class));
        verifyNoInteractions(deductRecordMapper);
    }
}
