package com.javaup.mq.publisher;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.OutboxPublishResult;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.javaup.mq.OutboxPublishResult.Outcome.*;

@Component
public class OrderResultOutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    public OrderResultOutboxPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${floworder.mq.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMillis
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = Math.max(1, confirmTimeoutMillis);
    }

    public OutboxPublishResult publish(MqOutboxEntity outbox, String claimToken) {
        long startedNanos = System.nanoTime();
        CorrelationData correlationData = new CorrelationData(
                outbox.getMessageId() + ":" + claimToken
        );

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

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            long latencyMillis = elapsedMillis(startedNanos);

            if (correlationData.getReturned() != null) {
                return OutboxPublishResult.failed(
                        RETURNED,
                        "消息无法路由到队列: " + correlationData.getReturned().getReplyText(),
                        latencyMillis
                );
            }
            if (!confirm.isAck()) {
                return OutboxPublishResult.failed(
                        NACK,
                        "Broker NACK: " + confirm.getReason(),
                        latencyMillis
                );
            }
            return OutboxPublishResult.ack(latencyMillis);
        } catch (TimeoutException exception) {
            return OutboxPublishResult.failed(
                    TIMEOUT,
                    "等待Publisher Confirm超时",
                    elapsedMillis(startedNanos)
            );
        } catch (Exception exception) {
            return OutboxPublishResult.failed(
                    EXCEPTION,
                    exception.getMessage(),
                    elapsedMillis(startedNanos)
            );
        }
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
