package com.javaup.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.exception.BizException;
import com.javaup.mq.service.OrderCreateMessageService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

import static com.javaup.constant.OrderMqConstant.ORDER_CREATE_QUEUE;
import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID;

@Slf4j
@Component
public class OrderCreateConsumer {

    private static final int MAX_ATTEMPTS = 3;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderCreateMessageService createMessageService;

    @RabbitListener(
            queues = ORDER_CREATE_QUEUE,
            containerFactory = "orderCreateListenerContainerFactory"
    )
    public void consume(Message rabbitMessage, Channel channel) throws IOException {
        // Broker/Channel 生成，用来标识这次投递要被确认
        long deliveryTag = rabbitMessage.getMessageProperties().getDeliveryTag();
        OrderCreateMessage command;
        try{
            command = objectMapper.readValue(rabbitMessage.getBody(), OrderCreateMessage.class);
        }catch(Exception e){
            log.error("订单创建消息反序列化失败");
            channel.basicReject(deliveryTag,false);
            return;
        }
        putTraceContext(command);
        log.info("收到订单创建消息，messageId= {},requestId = {},deductNo = {},orderNo = {}",
                command.getMessageId(),
                command.getData() == null ? null : command.getData().getRequestId(),
                command.getData() == null ? null : command.getData().getDeductNo(),
                command.getData() == null ? null : command.getData().getOrderNo()
        );
        try{
            Exception lastException = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try{
                    // rabbitmq消费消息常见订单
                    createMessageService.consume(command);
                    channel.basicAck(deliveryTag,false);
                    return;
                }catch (BizException bizException){// 明确的业务异常
                    try{
                        // 记录失败消息
                        createMessageService.recordFailure(command,bizException.getMessage());
                        channel.basicAck(deliveryTag,false);
                        return;
                    } catch (Exception failException) {
                        lastException = failException;
                    }
                }catch (Exception e){
                    lastException = e;
                }
                if(attempt < MAX_ATTEMPTS){
                    try{
                        Thread.sleep(attempt * 200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        lastException = e;
                        break;// 当前rabbitmq线程已经被要求停止了，就不要继续重试了。
                    }
                }
            }
            log.error("订单创建消息消费失败, messageId={}",
                    command.getMessageId(),
                    lastException);
            channel.basicNack(deliveryTag,false,false);
        }finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    /**
     * 获取消息体中的追踪ID然后赋值给当前的线程
     */
    private void putTraceContext(OrderCreateMessage command) {
        if(StringUtils.hasText(command.getTraceId())){
            MDC.put(TRACE_ID,command.getTraceId());
        }
        if(command.getData() != null && StringUtils.hasText(command.getData().getRequestId())){
            MDC.put(REQUEST_ID,command.getData().getRequestId());
        }
    }
}
