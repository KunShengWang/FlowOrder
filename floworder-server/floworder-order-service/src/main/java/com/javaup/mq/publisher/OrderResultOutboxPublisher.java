package com.javaup.mq.publisher;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class OrderResultOutboxPublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private MqOutboxService mqOutboxService;

    public void publish(MqOutboxEntity outbox) {
        CorrelationData correlationData = new CorrelationData(outbox.getMessageId());

        try {
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(outbox.getMessageId());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

            Message message = new Message(outbox.getContent().getBytes(StandardCharsets.UTF_8), properties);

            rabbitTemplate.send(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    message,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker NACK: " + confirm.getReason());
            }

            if (correlationData.getReturned() != null) {
                throw new IllegalStateException("消息无法路由到队列");
            }
        } catch (Exception exception) {
            log.error(
                    "Outbox消息发送失败, id={}, messageId={}",
                    outbox.getId(),
                    outbox.getMessageId(),
                    exception
            );

            mqOutboxService.markFailed(outbox.getId(), outbox.getRetryCount(), exception.getMessage());
            return;
        }

        // Confirm ACK且没有Return，才标记发送成功
        mqOutboxService.markSent(outbox.getId());
    }
}