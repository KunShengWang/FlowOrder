package com.javaup.resource.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.resource.mq.service.OrderResultMessageService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.javaup.constant.OrderMqConstant.ORDER_RESULT_QUEUE;
import static com.javaup.constant.RedisConstant.FLOWORDER_STOCK;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID;

@Slf4j
@Component
public class OrderResultConsumer {

    private static final int MAX_ATTEMPTS = 3;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderResultMessageService resultService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(
            queues = ORDER_RESULT_QUEUE,
            containerFactory = "orderResultListenerContainerFactory"
    )
    public void consume(Message rabbitMessage, Channel channel) throws IOException {
        long deliveryTag = rabbitMessage.getMessageProperties().getDeliveryTag();
        OrderCreateResultMessage result;
        try{
            result = objectMapper.readValue(rabbitMessage.getBody(),OrderCreateResultMessage.class);
        }catch (Exception exception) {
            log.error("订单结果消息反序列化失败", exception);
            channel.basicReject(deliveryTag, false);
            return;
        }
        putTraceContext(result);
        log.info(
                "收到订单结果消息, messageId={}, requestId={}, deductNo={}, orderNo={}, success={}",
                result.getMessageId(),
                result.getRequestId(),
                result.getDeductNo(),
                result.getOrderNo(),
                result.getSuccess()
        );
        try{
            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    Long stockItemId = resultService.handle(result);
                    if (stockItemId != null) {
                        stringRedisTemplate.delete(FLOWORDER_STOCK + stockItemId);
                    }
                    channel.basicAck(deliveryTag, false);
                    return;
                } catch (IllegalArgumentException protocolException) {
                    log.error("订单结果消息协议错误, messageId={}",
                            result.getMessageId(), protocolException);
                    channel.basicReject(deliveryTag, false);
                    return;
                } catch (Exception exception) {
                    lastException = exception;
                    log.warn("订单结果处理失败, messageId={}, attempt={}",
                            result.getMessageId(), attempt, exception);
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
                    "订单结果处理失败, messageId={}",
                    result.getMessageId(),
                    lastException
            );
            channel.basicNack(deliveryTag, false, false);
        }finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private void putTraceContext(OrderCreateResultMessage result) {
        if (StringUtils.hasText(result.getTraceId())) {
            MDC.put(TRACE_ID, result.getTraceId());
        }
        if (StringUtils.hasText(result.getRequestId())) {
            MDC.put(REQUEST_ID, result.getRequestId());
        }
    }
}
