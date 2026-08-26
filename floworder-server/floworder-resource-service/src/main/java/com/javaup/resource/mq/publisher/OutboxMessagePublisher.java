package com.javaup.resource.mq.publisher;

import com.javaup.mq.OutboxPublishResult;
import com.javaup.resource.entity.MqOutboxEntity;
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
public class OutboxMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    public OutboxMessagePublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${floworder.mq.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMillis
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = Math.max(1, confirmTimeoutMillis);
    }

    public OutboxPublishResult publish(MqOutboxEntity outbox, String claimToken) {
        long startedNanos = System.nanoTime();
        // Return 相关 CorrelationData ID 必须标识单次发送尝试；业务 messageId 仍保持不变。
        CorrelationData correlationData = new CorrelationData(
                outbox.getMessageId() + ":" + claimToken
        );

        try {
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(outbox.getMessageId());// 设置消息 ID,消费者可以通过这个 ID 做幂等控制，避免重复消费。
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);// 表示消息体是 JSON
            properties.setContentEncoding(StandardCharsets.UTF_8.name());// 表示消息内容按 UTF-8 编码
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);// 表示消息持久化。只要队列也是 durable，交换机/队列配置正确，RabbitMQ 重启后消息理论上不会直接丢。
            // 封装成 RabbitMQ 的 Message 对象
            Message message = new Message(outbox.getContent().getBytes(StandardCharsets.UTF_8), properties);
            // 发送消息给 RabbitMQ
            rabbitTemplate.send(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    message,
                    correlationData
            );
            // 在同步等待 RabbitMQ Broker 对消息的确认结果
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            long latencyMillis = elapsedMillis(startedNanos);
            // Spring AMQP 3.2.x 保证 ReturnedMessage 在 Confirm future 完成前填充。
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
