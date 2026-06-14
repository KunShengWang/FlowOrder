package com.javaup.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.exception.BizException;
import com.javaup.mq.service.OrderCreateMessageService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.javaup.constant.OrderMqConstant.ORDER_CREATE_QUEUE;

@Component
@Slf4j
public class OrderCreateConsumer {

    private static final int MAX_ATTEMPTS = 3;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderCreateMessageService messageService;

    @RabbitListener(queues = ORDER_CREATE_QUEUE)
    public void consume(Message rabbitMessage, Channel channel) throws IOException {
        // Broker/Channel 生成，用来标识这次投递要被确认
        long deliveryTag = rabbitMessage.getMessageProperties().getDeliveryTag();

        OrderCreateMessage command;
        try {
            command = objectMapper.readValue(rabbitMessage.getBody(), OrderCreateMessage.class);
        } catch (Exception exception) {
            log.error("订单创建消息反序列化失败", exception);
            channel.basicReject(deliveryTag, false);
            return;
        }

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                messageService.consume(command);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (BizException businessException) {// 明确的业务异常，创建订单这条命令已经有了明确的失败结果，并且失败结果已经可靠保存，不需要再次消费。
                try {
                    messageService.recordFailure(command, businessException.getMessage());
                    channel.basicAck(deliveryTag, false);
                    return;
                } catch (Exception failureException) {
                    lastException = failureException;
                }
            } catch (Exception exception) {// 技术异常，它可能只是暂时故障，可以重试
                lastException = exception;
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
                "订单创建消息消费失败, messageId={}",
                command.getMessageId(),
                lastException
        );
        channel.basicNack(deliveryTag, false, false);
    }
}
