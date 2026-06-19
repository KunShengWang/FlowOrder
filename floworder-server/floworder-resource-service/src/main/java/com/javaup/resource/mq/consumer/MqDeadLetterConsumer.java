package com.javaup.resource.mq.consumer;

import com.javaup.resource.mq.service.MqDeadLetterService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqDeadLetterConsumer {

    private final MqDeadLetterService deadLetterService;

    @RabbitListener(
            queues = {
                    ORDER_CREATE_DLQ,
                    ORDER_RESULT_DLQ,
                    ORDER_STATE_DLQ
            },
            containerFactory = "deadLetterListenerContainerFactory"
    )
    public void consume(Message rabbitMessage, Channel channel) throws IOException {
        long deliveryTag = rabbitMessage.getMessageProperties().getDeliveryTag();
        String deadQueue = rabbitMessage.getMessageProperties().getConsumerQueue();// Broker 自动填冲是哪个队列的消息
        String messageId = rabbitMessage.getMessageProperties().getMessageId();
        if (!StringUtils.hasText(messageId)) {
            messageId = UUID.nameUUIDFromBytes(rabbitMessage.getBody()).toString();
        }
        String content = new String(rabbitMessage.getBody(), StandardCharsets.UTF_8);
        Object xDeath = rabbitMessage.getMessageProperties().getHeaders().get("x-death");
        String deathReason = xDeath == null ? null : xDeath.toString();

        try {
            deadLetterService.record(deadQueue, messageId, content, deathReason);
            channel.basicAck(deliveryTag, false);
            log.warn("Dead letter persisted, queue={}, messageId={}", deadQueue, messageId);
        } catch (Exception exception) {
            log.error("Dead letter persistence failed, queue={}, messageId={}",
                    deadQueue, messageId, exception);
            // 此DLQ已无其他DLX。设置为true会重新入队，以免丢失唯一副本。
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
