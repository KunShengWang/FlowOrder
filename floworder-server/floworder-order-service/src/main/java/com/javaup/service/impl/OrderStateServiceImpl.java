package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.dto.OrderStateDetailDto;
import com.javaup.dto.OrderStatusLogDto;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.entity.OrderStatusLogEntity;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mapper.OrderStatusLogMapper;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.OrderStateService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.enums.OrderEventEnum.CANCEL;
import static com.javaup.enums.OrderEventEnum.CONFIRM;
import static com.javaup.enums.OrderOperatorTypeEnum.USER;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo, Long userId) {
        checkParams(orderNo, userId);
        ReservationOrderEntity order = getOrder(orderNo, userId);
        if (Objects.equals(order.getStatus(), CONFIRMED.getCode())) {
            return;
        }
        if (!Objects.equals(order.getStatus(), RESERVED.getCode())) {
            throw new BizException("当前订单状态不允许确认");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(
                null,
                Wrappers.<ReservationOrderEntity>lambdaUpdate()
                        .eq(ReservationOrderEntity::getId, order.getId())
                        .eq(ReservationOrderEntity::getStatus, RESERVED.getCode())
                        .gt(ReservationOrderEntity::getExpireTime, now)
                        .set(ReservationOrderEntity::getStatus, CONFIRMED.getCode())
                        .set(ReservationOrderEntity::getConfirmedAt, now)
                        .set(ReservationOrderEntity::getUpdatedAt, now)
                        // version = version + 1 用来记录订单被成功修改的次数，并为后续乐观锁提供版本号
                        .setSql("version = version + 1")

        );
        if(rows != 1){
            handleConflict(orderNo,userId,CONFIRMED.getCode());
            return;
        }
        saveStatusLog(orderNo, RESERVED.getCode(), CONFIRMED.getCode(),
                CONFIRM.getCode(), USER.getCode(), "用户确认订单");
        saveStateOutbox(order, CONFIRMED.getCode(), ORDER_CONFIRMED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, Long userId, String reason) {
        checkParams(orderNo, userId);
        ReservationOrderEntity order = getOrder(orderNo, userId);
        // 当前订单已被取消
        if(Objects.equals(order.getStatus(),CANCELLED.getCode())){
            return;
        }
        // 当前订单状态不是已预约状态
        if(!Objects.equals(order.getStatus(), RESERVED.getCode())){
            throw new BizException("当前订单状态不允许取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.hasText(reason) && reason.length() > 255) {
            throw new BizException("取消原因不能超过255个字符");
        }
        String cancelReason = StringUtils.hasText(reason) ? reason : "用户取消";
        int rows = orderMapper.update(
                null,
                Wrappers.<ReservationOrderEntity>lambdaUpdate()
                        .eq(ReservationOrderEntity::getId, order.getId())
                        .eq(ReservationOrderEntity::getStatus, RESERVED.getCode())
                        .gt(ReservationOrderEntity::getExpireTime, now)
                        .set(ReservationOrderEntity::getStatus, CANCELLED.getCode())
                        .set(ReservationOrderEntity::getCanceledAt, now)
                        .set(ReservationOrderEntity::getCancelReason, cancelReason)
                        .set(ReservationOrderEntity::getUpdatedAt, now)
                        // version = version + 1 用来记录订单被成功修改的次数，并为后续乐观锁提供版本号
                        .setSql("version = version + 1")
        );
        if(rows != 1){
            handleConflict(orderNo, userId, CANCELLED.getCode());
            return;
        }
        saveStatusLog(orderNo, RESERVED.getCode(), CANCELLED.getCode(),
                CANCEL.getCode(), USER.getCode(), cancelReason);
        saveStateOutbox(order, CANCELLED.getCode(), ORDER_CANCELLED);
    }

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

    @Override
    @Transactional(readOnly = true)
    public OrderStateDetailDto detail(String orderNo, Long userId) {
        checkParams(orderNo, userId);
        ReservationOrderEntity order = getOrder(orderNo, userId);

        List<OrderStatusLogDto> logs = statusLogMapper.selectList(
                Wrappers.<OrderStatusLogEntity>lambdaQuery()
                        .eq(OrderStatusLogEntity::getOrderNo, orderNo)
                        .orderByAsc(OrderStatusLogEntity::getCreatedAt)
                        .orderByAsc(OrderStatusLogEntity::getId)
        ).stream().map(this::toLogDto).toList();

        OrderStateDetailDto dto = new OrderStateDetailDto();
        dto.setOrderNo(order.getOrderNo());
        dto.setUserId(order.getUserId());
        dto.setStockItemId(order.getStockItemId());
        dto.setQuantity(order.getQuantity());
        dto.setStatus(order.getStatus());
        dto.setExpireTime(order.getExpireTime());
        dto.setConfirmedAt(order.getConfirmedAt());
        dto.setCanceledAt(order.getCanceledAt());
        dto.setCancelReason(order.getCancelReason());
        dto.setVersion(order.getVersion());
        dto.setStatusLogs(logs);
        return dto;
    }

    private OrderStatusLogDto toLogDto(OrderStatusLogEntity entity) {
        OrderStatusLogDto dto = new OrderStatusLogDto();
        dto.setFromStatus(entity.getFromStatus());
        dto.setToStatus(entity.getToStatus());
        dto.setEvent(entity.getEvent());
        dto.setOperatorType(entity.getOperatorType());
        dto.setRemark(entity.getRemark());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private void checkParams(String orderNo, Long userId) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BizException("订单号不能为空");
        }
        if (userId == null) {
            throw new BizException("用户ID不能为空");
        }
    }

    private ReservationOrderEntity getOrder(String orderNo, Long userId){
        ReservationOrderEntity order = orderMapper.selectOne(
                Wrappers.<ReservationOrderEntity>lambdaQuery()
                        .eq(ReservationOrderEntity::getOrderNo, orderNo)
                        .eq(ReservationOrderEntity::getUserId, userId)
                        .eq(ReservationOrderEntity::getDeleted, 0)
                        .last("limit 1")
        );
        if(order == null){
            throw new BizException("订单不存在");
        }
        return order;
    }

    private void handleConflict(String orderNo, Long userId, Integer targetStatus) {
        ReservationOrderEntity latest = orderMapper.selectOne(
                Wrappers.<ReservationOrderEntity>lambdaQuery()
                        .eq(ReservationOrderEntity::getOrderNo, orderNo)
                        .eq(ReservationOrderEntity::getUserId, userId)
                        .last("for update")
        );
        if(latest != null && Objects.equals(latest.getStatus(),targetStatus)){
            return;
        }
        if(latest != null && Objects.equals(latest.getStatus(),RESERVED.getCode()) && !latest.getExpireTime().isAfter(LocalDateTime.now())){
            throw new BizException("订单已过期，等待系统关闭");
        }
        throw new BizException("订单状态已变化，请勿重复操作");
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
