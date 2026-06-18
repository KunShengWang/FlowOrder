package com.javaup.resource.mq.publisher;

import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.service.MqOutboxService;
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

@Slf4j
@Component
public class OutboxMessagePublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private MqOutboxService mqOutboxService;

    public void publish(MqOutboxEntity outbox) {
        // 消息发送跟踪 ID
        // TODO 这个东西暂时没用到，是否可以把现在的同步改为异步
        CorrelationData correlationData = new CorrelationData(outbox.getMessageId());

        try{
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(outbox.getMessageId());// 设置消息 ID,消费者可以通过这个 ID 做幂等控制，避免重复消费。
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);// 表示消息体是 JSON
            properties.setContentEncoding(StandardCharsets.UTF_8.name());// 表示消息内容按 UTF-8 编码
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);// 表示消息持久化。只要队列也是 durable，交换机/队列配置正确，RabbitMQ 重启后消息理论上不会直接丢。
            // 封装成 RabbitMQ 的 Message 对象
            Message message = new Message(outbox.getContent().getBytes(StandardCharsets.UTF_8),properties);
            rabbitTemplate.send(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    message,
                    correlationData
            );
            // 同步等待 RabbitMQ Broker 对消息的确认结果
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            // Broker 没有确认接收该消息，发布失败
            if(!confirm.isAck()){
                throw new IllegalStateException("Broker NACK: " + confirm.getReason());
            }
            // 没有找到匹配的队列
            if(correlationData.getReturned() != null){
                throw new IllegalStateException("消息无法路由到队列");
            }
        } catch (Exception e) {
            log.error("消息发送失败，id = {},messageId = {}",outbox.getId(),outbox.getMessageId(),e);
            // 标记消息发送失败
            mqOutboxService.markFailed(outbox.getId(),outbox.getRetryCount(),e.getMessage());
            return;
        }
        // Confirm ACK且没有Return，才标记发送成功
        mqOutboxService.markSent(outbox.getId());
    }
}
