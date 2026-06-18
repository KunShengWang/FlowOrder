package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.entity.OrderStatusLogEntity;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mapper.OrderStatusLogMapper;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.OrderStateService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.enums.OrderStatusEnum.*;
import com.javaup.enums.OrderEventEnum;
import java.util.List;

import static com.javaup.enums.OrderOperatorTypeEnum.SYSTEM;
import org.slf4j.MDC;

import static com.javaup.trace.TraceConstant.TRACE_ID;

@Service
public class OrderStateServiceImpl implements OrderStateService {

    @Resource
    private ReservationOrderMapper orderMapper;

    @Resource
    private OrderStatusLogMapper statusLogMapper;

    @Resource
    private MqOutboxMapper outboxMapper;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 查找过期订单的id
     */
    @Override
    public List<Long> findExpiredOrderIds(int batchSize) {
        int limit = Math.min(Math.max(batchSize, 1), 500);
        LocalDateTime now = LocalDateTime.now();
        return orderMapper.selectList(
                Wrappers.<ReservationOrderEntity>lambdaQuery()
                        .select(ReservationOrderEntity::getId)
                        .eq(ReservationOrderEntity::getStatus, RESERVED.getCode())
                        .eq(ReservationOrderEntity::getDeleted, 0)
                        .isNotNull(ReservationOrderEntity::getExpireTime)
                        .le(ReservationOrderEntity::getExpireTime, now)
                        .orderByAsc(ReservationOrderEntity::getExpireTime)
                        .last("limit " + limit)
        ).stream().map(ReservationOrderEntity::getId).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean timeout(Long orderId) {
        if (orderId == null) {
            return false;
        }
        ReservationOrderEntity order = orderMapper.selectById(orderId);
        LocalDateTime now = LocalDateTime.now();
        if (order == null
                || !Objects.equals(order.getDeleted(), 0)
                || !Objects.equals(order.getStatus(), RESERVED.getCode())
                || order.getExpireTime() == null
                || order.getExpireTime().isAfter(now)) {
            return false;
        }
        String reason = "订单超时自动关闭";
        int rows = orderMapper.update(
                null,
                Wrappers.<ReservationOrderEntity>lambdaUpdate()
                        .eq(ReservationOrderEntity::getId, orderId)
                        .eq(ReservationOrderEntity::getStatus, RESERVED.getCode())
                        .eq(ReservationOrderEntity::getDeleted, 0)
                        .le(ReservationOrderEntity::getExpireTime, now)
                        .set(ReservationOrderEntity::getStatus, TIMEOUT.getCode())
                        .set(ReservationOrderEntity::getCanceledAt, now)
                        .set(ReservationOrderEntity::getCancelReason, reason)
                        .set(ReservationOrderEntity::getUpdatedAt, now)
                        .setSql("version = version + 1")
        );
        if (rows != 1) {
            return false;
        }
        saveStatusLog(
                order.getOrderNo(),
                RESERVED.getCode(),
                TIMEOUT.getCode(),
                OrderEventEnum.TIMEOUT.getCode(),
                SYSTEM.getCode(),
                reason
        );
        saveStateOutbox(order, TIMEOUT.getCode(), ORDER_TIMEOUT);
        return true;
    }

    private void saveStatusLog(
            String orderNo, Integer fromStatus, Integer toStatus,
            String event, String operatorType, String remark) {

        OrderStatusLogEntity log = new OrderStatusLogEntity();
        log.setOrderNo(orderNo);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setEvent(event);
        log.setOperatorType(operatorType);
        log.setRemark(remark);
        log.setCreatedAt(LocalDateTime.now());

        if (statusLogMapper.insert(log) != 1) {
            throw new IllegalStateException("订单状态日志保存失败");
        }
    }

    private void saveStateOutbox(ReservationOrderEntity order, Integer targetStatus, String eventType) {
        LocalDateTime now = LocalDateTime.now();
        String messageId = UUID.randomUUID().toString();

        OrderStateChangedMessage message = new OrderStateChangedMessage();
        message.setMessageId(messageId);
        message.setTraceId(MDC.get(TRACE_ID));
        message.setEventType(eventType);
        message.setRequestId(order.getRequestId());
        message.setOrderNo(order.getOrderNo());
        message.setDeductNo(order.getDeductNo());
        message.setStockItemId(order.getStockItemId());
        message.setQuantity(order.getQuantity());
        message.setFromStatus(RESERVED.getCode());
        message.setToStatus(targetStatus);
        message.setOccurredAt(now);

        String content;
        try{
            content = objectMapper.writeValueAsString(message);
        }catch (JsonProcessingException exception) {
            throw new IllegalStateException("订单状态消息序列化失败", exception);
        }

        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(messageId);
        outbox.setProducerService(ORDER_SERVICE);
        outbox.setBizKey(order.getOrderNo());
        outbox.setMessageType(eventType);
        outbox.setExchangeName(ORDER_STATE_EXCHANGE);
        outbox.setRoutingKey(ORDER_STATE_ROUTING_KEY);
        outbox.setContent(content);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);

        if (outboxMapper.insert(outbox) != 1) {
            throw new IllegalStateException("订单状态Outbox保存失败");
        }
    }
}