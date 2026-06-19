package com.javaup.resource.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.mq.service.OrderStateMessageService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.javaup.constant.OrderMqConstant.ORDER_STATE_QUEUE;
import static com.javaup.constant.RedisConstant.FLOWORDER_STOCK;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID;

@Slf4j
@Component
public class OrderStateConsumer {

    private static final int MAX_ATTEMPTS = 3;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderStateMessageService messageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MqDeadLetterService deadLetterService;

    @RabbitListener(
            queues = ORDER_STATE_QUEUE,
            containerFactory = "orderStateListenerContainerFactory"
    )
    public void consume(Message rabbitMessage, Channel channel) throws IOException {
        long deliveryTag = rabbitMessage.getMessageProperties().getDeliveryTag();
        OrderStateChangedMessage message;
        try{
            message = objectMapper.readValue(rabbitMessage.getBody(),OrderStateChangedMessage.class);
        }catch (Exception exception) {
            log.error("订单状态消息反序列化失败", exception);
            channel.basicReject(deliveryTag, false);
            return;
        }
        putTraceContext(message);
        try{
            log.info(
                    "收到订单状态消息, messageId={}, requestId={}, orderNo={}, deductNo={}, eventType={}",
                    message.getMessageId(),
                    message.getRequestId(),
                    message.getOrderNo(),
                    message.getDeductNo(),
                    message.getEventType()
            );
            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try{
                    Long stockItemId = messageService.handle(message);
                    if (stockItemId != null) {
                        stringRedisTemplate.delete(FLOWORDER_STOCK + stockItemId);
                    }
                    deadLetterService.resolveOrderState(message);
                    channel.basicAck(deliveryTag, false);
                    return;
                }catch (IllegalArgumentException protocolException) {
                    log.error(
                            "订单状态消息协议错误, messageId={}",
                            message.getMessageId(),
                            protocolException
                    );
                    channel.basicReject(deliveryTag, false);
                    return;
                } catch (Exception exception) {
                    lastException = exception;
                    log.warn(
                            "订单状态消息处理失败, messageId={}, attempt={}",
                            message.getMessageId(),
                            attempt,
                            exception
                    );
                }
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(attempt * 200L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        lastException = exception;
                        break;
                    }
                }
            }
            log.error(
                    "订单状态消息最终处理失败, messageId={}",
                    message.getMessageId(),
                    lastException
            );

            channel.basicNack(deliveryTag, false, false);
        }finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private void putTraceContext(OrderStateChangedMessage message) {
        if (StringUtils.hasText(message.getTraceId())) {
            MDC.put(TRACE_ID, message.getTraceId());
        }
        if (StringUtils.hasText(message.getRequestId())) {
            MDC.put(REQUEST_ID, message.getRequestId());
        }
    }
}
