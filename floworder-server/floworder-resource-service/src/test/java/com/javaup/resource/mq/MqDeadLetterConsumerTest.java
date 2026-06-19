package com.javaup.resource.mq;

import com.javaup.resource.mq.consumer.MqDeadLetterConsumer;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static com.javaup.constant.OrderMqConstant.ORDER_CREATE_DLQ;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqDeadLetterConsumerTest {

    @Mock
    private MqDeadLetterService deadLetterService;

    @Mock
    private Channel channel;

    @InjectMocks
    private MqDeadLetterConsumer consumer;

    @Test
    void persistenceSuccessShouldAck() throws Exception {
        Message message = message(7L, "message-1");

        consumer.consume(message, channel);

        verify(deadLetterService).record(
                eq(ORDER_CREATE_DLQ),
                eq("message-1"),
                eq("{}"),
                anyString()
        );
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void persistenceFailureShouldRequeue() throws Exception {
        Message message = message(8L, "message-2");
        doThrow(new IllegalStateException("database unavailable"))
                .when(deadLetterService)
                .record(anyString(), anyString(), anyString(), anyString());

        consumer.consume(message, channel);

        verify(channel).basicNack(8L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message message(long deliveryTag, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setConsumerQueue(ORDER_CREATE_DLQ);
        properties.setMessageId(messageId);
        properties.setHeader("x-death", "rejected");
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
