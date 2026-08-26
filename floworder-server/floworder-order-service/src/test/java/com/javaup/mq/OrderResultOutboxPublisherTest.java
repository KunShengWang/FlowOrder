package com.javaup.mq;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.publisher.OrderResultOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static com.javaup.mq.OutboxPublishResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderResultOutboxPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @Test
    void ackAndReturnShouldResolveToReturnedInsteadOfSuccess() {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    message, 312, "NO_ROUTE", "test.exchange", "test.key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxPublishResult result = new OrderResultOutboxPublisher(rabbitTemplate, 100)
                .publish(outbox(), "claim-token");

        assertEquals(RETURNED, result.outcome());
        assertFalse(result.successful());
    }

    @Test
    void ackWithoutReturnShouldResolveToSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxPublishResult result = new OrderResultOutboxPublisher(rabbitTemplate, 100)
                .publish(outbox(), "claim-token");

        assertEquals(ACK_ROUTED, result.outcome());
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
