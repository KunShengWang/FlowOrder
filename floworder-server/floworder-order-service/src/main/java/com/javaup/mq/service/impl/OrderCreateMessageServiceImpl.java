package com.javaup.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.entity.MqConsumeLogEntity;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.mapper.MqConsumeLogMapper;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mq.service.OrderCreateMessageService;
import com.javaup.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;

@Service
public class OrderCreateMessageServiceImpl implements OrderCreateMessageService {

    @Resource
    private MqConsumeLogMapper mqConsumeLogMapper;

    @Resource
    private OrderService orderService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MqOutboxMapper outboxMapper;

    /**
     * rabbitmq消费消息常见订单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(OrderCreateMessage command) {
        // 插入消费日志
        MqConsumeLogEntity log = insertConsumeLog(command);
        if(log == null){
           return;
        }
        // 创建订单
        String orderNo = orderService.create(command.getData());
        // 构建订单创建结果消息，方便resource-service消费
        MqOutboxEntity resultOutbox = buildResultOutbox(command,true,orderNo,null);
        if (outboxMapper.insert(resultOutbox) != 1) {
            throw new IllegalStateException("订单结果Outbox保存失败");
        }
        // 消息标记为已消费
        markConsumed(log.getId());
    }

    /**
     * 记录失败消息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(OrderCreateMessage command, String error) {
        // 插入消费日志
        MqConsumeLogEntity log = insertConsumeLog(command);
        if(log == null){
            return;
        }
        // 构建订单创建结果消息，方便resource-service消费
        MqOutboxEntity resultOutbox = buildResultOutbox(command,false,null,error);
        if(outboxMapper.insert(resultOutbox) != 1){
            throw new IllegalStateException("订单结果Outbox保存失败");
        }
        // 消息标记为已消费
        markConsumed(log.getId());
    }

    /**
     * 插入消费日志
     */
    private MqConsumeLogEntity insertConsumeLog(OrderCreateMessage command) {
        MqConsumeLogEntity old = mqConsumeLogMapper.selectOne(
                Wrappers.<MqConsumeLogEntity>lambdaQuery()
                        .eq(MqConsumeLogEntity::getMessageId,command.getMessageId())
                        .eq(MqConsumeLogEntity::getConsumerGroup,ORDER_CREATE_CONSUMER)
                        .last("limit 1")
        );
        if(old != null && Objects.equals(old.getStatus(),10)){
            return null;
        }
        MqConsumeLogEntity log = new MqConsumeLogEntity();
        log.setMessageId(command.getMessageId());
        log.setConsumerGroup(ORDER_CREATE_CONSUMER);
        log.setMessageType(command.getEventType());
        log.setBizKey(command.getData().getDeductNo());
        log.setStatus(0);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        // 尝试插入
        try{
            mqConsumeLogMapper.insert(log);
        }catch (DuplicateKeyException e){
            // 再查一遍
            old = mqConsumeLogMapper.selectOne(
                    Wrappers.<MqConsumeLogEntity>lambdaQuery()
                            .eq(MqConsumeLogEntity::getMessageId,command.getMessageId())
                            .eq(MqConsumeLogEntity::getConsumerGroup,ORDER_CREATE_CONSUMER)
                            .last("limit 1")
            );
            if(old != null && Objects.equals(old.getStatus(),10)){
                return null;
            }
            throw e;
        }
        return log;
    }

    /**
     * 构建订单创建结果消息
     */
    private MqOutboxEntity buildResultOutbox(OrderCreateMessage command, boolean success, String orderNo, String reason) {
        OrderCreateResultMessage result = new OrderCreateResultMessage();
        String resultMessageId = UUID.randomUUID().toString();

        result.setMessageId(resultMessageId);
        result.setTraceId(command.getTraceId());
        result.setEventType(success ? ORDER_CREATE_SUCCEEDED : ORDER_CREATE_FAILED);
        result.setOccurredAt(LocalDateTime.now());
        result.setRequestId(command.getData().getRequestId());
        result.setDeductNo(command.getData().getDeductNo());
        result.setOrderNo(orderNo);
        result.setSuccess(success);
        result.setErrorMessage(reason);

        String content;
        try{
            content = objectMapper.writeValueAsString(result);
        }catch (JsonProcessingException exception) {
            throw new IllegalStateException("订单结果消息序列化失败", exception);
        }

        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(resultMessageId);
        outbox.setProducerService(ORDER_SERVICE);
        outbox.setBizKey(command.getData().getDeductNo());
        outbox.setMessageType(result.getEventType());
        outbox.setExchangeName(ORDER_RESULT_EXCHANGE);
        outbox.setRoutingKey(ORDER_RESULT_ROUTING_KEY);
        outbox.setContent(content);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(LocalDateTime.now());
        outbox.setCreatedAt(LocalDateTime.now());
        outbox.setUpdatedAt(LocalDateTime.now());
        return outbox;
    }

    /**
     * 消息标记为已消费
     */
    private void markConsumed(Long id) {
        int rows = mqConsumeLogMapper.update(
                null,
                Wrappers.<MqConsumeLogEntity>lambdaUpdate()
                        .eq(MqConsumeLogEntity::getId,id)
                        .eq(MqConsumeLogEntity::getStatus,0)
                        .set(MqConsumeLogEntity::getStatus,10)
        );
        if(rows != 1){
            throw new IllegalStateException("消费日志状态更新失败");
        }
    }
}
