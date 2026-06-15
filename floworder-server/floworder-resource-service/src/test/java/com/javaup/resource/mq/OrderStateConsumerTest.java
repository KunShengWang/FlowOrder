package com.javaup.resource.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.resource.mq.consumer.OrderStateConsumer;
import com.javaup.resource.mq.service.OrderStateMessageService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.javaup.constant.OrderMqConstant.ORDER_CANCELLED;
import static com.javaup.enums.OrderStatusEnum.CANCELLED;
import static com.javaup.enums.OrderStatusEnum.RESERVED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStateConsumerTest {

    @Mock
    private OrderStateMessageService messageService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private Channel channel;

    @InjectMocks
    private OrderStateConsumer consumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        try {
            var field = OrderStateConsumer.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(consumer, objectMapper);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void successShouldDeleteRedisAndAck() throws Exception {
        OrderStateChangedMessage command = command();
        when(messageService.handle(any())).thenReturn(11L);

        consumer.consume(rabbitMessage(command, 7L), channel);

        verify(stringRedisTemplate).delete(anyString());
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void protocolFailureShouldRejectWithoutRetry() throws Exception {
        OrderStateChangedMessage command = command();
        when(messageService.handle(any())).thenThrow(new IllegalArgumentException("bad protocol"));

        consumer.consume(rabbitMessage(command, 8L), channel);

        verify(messageService, times(1)).handle(any());
        verify(channel).basicReject(8L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void transientFailureShouldRetryThreeTimesThenNack() throws Exception {
        OrderStateChangedMessage command = command();
        when(messageService.handle(any())).thenThrow(new IllegalStateException("database unavailable"));

        consumer.consume(rabbitMessage(command, 9L), channel);

        verify(messageService, times(3)).handle(any());
        verify(channel).basicNack(9L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void redisDeleteFailureMustNotAck() throws Exception {
        OrderStateChangedMessage command = command();
        when(messageService.handle(any())).thenReturn(11L);
        when(stringRedisTemplate.delete(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        consumer.consume(rabbitMessage(command, 10L), channel);

        verify(messageService, times(3)).handle(any());
        verify(stringRedisTemplate, times(3)).delete(anyString());
        verify(channel).basicNack(10L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private Message rabbitMessage(OrderStateChangedMessage command, long deliveryTag) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(objectMapper.writeValueAsBytes(command), properties);
    }

    private OrderStateChangedMessage command() {
        OrderStateChangedMessage command = new OrderStateChangedMessage();
        command.setMessageId("message-1");
        command.setEventType(ORDER_CANCELLED);
        command.setOrderNo("order-1");
        command.setDeductNo("deduct-1");
        command.setStockItemId(11L);
        command.setQuantity(1);
        command.setFromStatus(RESERVED.getCode());
        command.setToStatus(CANCELLED.getCode());
        return command;
    }
}
