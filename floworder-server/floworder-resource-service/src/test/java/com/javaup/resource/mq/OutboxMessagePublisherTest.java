package com.javaup.resource.mq;

import com.javaup.mq.OutboxPublishResult;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.publisher.OutboxMessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static com.javaup.mq.OutboxPublishResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxMessagePublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @Test
    void ackWithoutReturnShouldBeSingleSuccessResult() {
        completeConfirm(true, null, false);
        OutboxPublishResult result = publisher(100).publish(outbox(), "token-a");
        assertEquals(ACK_ROUTED, result.outcome());
        assertTrue(result.successful());
    }

    @Test
    void ackWithReturnMustNotBecomeSentResult() {
        completeConfirm(true, null, true);
        OutboxPublishResult result = publisher(100).publish(outbox(), "token-b");
        assertEquals(RETURNED, result.outcome());
        assertFalse(result.successful());
    }

    @Test
    void nackShouldBeFailureResult() {
        completeConfirm(false, "broker rejected", false);
        OutboxPublishResult result = publisher(100).publish(outbox(), "token-c");
        assertEquals(NACK, result.outcome());
    }

    @Test
    void missingConfirmShouldTimeout() {
        OutboxPublishResult result = publisher(1).publish(outbox(), "token-d");
        assertEquals(TIMEOUT, result.outcome());
    }

    @Test
    void sendExceptionShouldBeFailureResult() {
        doThrow(new IllegalStateException("connection down"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        OutboxPublishResult result = publisher(100).publish(outbox(), "token-e");
        assertEquals(EXCEPTION, result.outcome());
    }

    private void completeConfirm(boolean ack, String reason, boolean returned) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        message, 312, "NO_ROUTE", "test.exchange", "test.key"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private OutboxMessagePublisher publisher(long timeoutMillis) {
        return new OutboxMessagePublisher(rabbitTemplate, timeoutMillis);
    }

    private MqOutboxEntity outbox() {
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setId(1L);
        outbox.setMessageId("message-1");
        outbox.setExchangeName("test.exchange");
        outbox.setRoutingKey("test.key");
        outbox.setContent("{}");
        return outbox;
    }
}
