package com.javaup.resource.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.resource.entity.MqConsumeLogEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.MqConsumeLogMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mq.service.OrderStateMessageService;
import com.javaup.resource.service.ReservationAdmissionService;
import com.javaup.resource.service.ReservationRequestService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.*;
import static com.javaup.enums.OrderStatusEnum.*;

@Service
public class OrderStateMessageServiceImpl implements OrderStateMessageService {

    @Resource
    private MqConsumeLogMapper mqConsumeLogMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private ReservationAdmissionService reservationAdmissionService;

    @Resource
    private ReservationRequestService reservationRequestService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long handle(OrderStateChangedMessage message) {
        validateMessage(message);
        StockDeductRecordEntity record = findRecord(message);
        validateRecord(message, record);
        MqConsumeLogEntity log = insertConsumeLog(message);
        boolean releaseEvent =
                ORDER_CANCELLED.equals(message.getEventType())
                        || ORDER_TIMEOUT.equals(message.getEventType());
        if (log == null) {
            return releaseEvent ? record.getStockItemId() : null;
        }
        if (ORDER_CONFIRMED.equals(message.getEventType())) {
            confirmStock(record);
        } else if (releaseEvent) {
            releaseStock(record);
        } else {
            throw new IllegalArgumentException("不支持的订单状态事件");
        }

        reservationRequestService.markOrderStateChanged(
                message.getRequestId(),
                message.getOrderNo(),
                message.getFromStatus(),
                message.getToStatus(),
                message.getEventType(),
                message.getOccurredAt()
        );

        markConsumed(log.getId());
        return releaseEvent ? record.getStockItemId() : null;
    }

    private StockDeductRecordEntity findRecord(OrderStateChangedMessage message){
        StockDeductRecordEntity stockDeductRecordEntity = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo,message.getDeductNo())
                        .last("limit 1")
        );
        if(stockDeductRecordEntity == null || !Objects.equals(stockDeductRecordEntity.getCreateMode(), 3)){
            throw new IllegalStateException("异步库存预扣记录不存在");
        }
        return stockDeductRecordEntity;
    }

    private MqConsumeLogEntity insertConsumeLog(OrderStateChangedMessage message){
        MqConsumeLogEntity log = new MqConsumeLogEntity();
        log.setMessageId(message.getMessageId());
        log.setConsumerGroup(ORDER_STATE_CONSUMER);
        log.setMessageType(message.getEventType());
        log.setBizKey(message.getDeductNo());
        log.setStatus(0);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        try{
            mqConsumeLogMapper.insert(log);
        }catch (DuplicateKeyException e){
            MqConsumeLogEntity mqConsumeLogEntity = mqConsumeLogMapper.selectOne(
                    Wrappers.<MqConsumeLogEntity>lambdaQuery()
                            .eq(MqConsumeLogEntity::getMessageId,message.getMessageId())
                            .eq(MqConsumeLogEntity::getConsumerGroup,ORDER_STATE_CONSUMER)
                            .last("limit 1")
            );
            if(mqConsumeLogEntity != null && Objects.equals(mqConsumeLogEntity.getStatus(),10)){
                return null;
            }
            throw e;
        }
        return log;
    }

    private void confirmStock(StockDeductRecordEntity record) {
        if (Objects.equals(record.getStatus(), SOLD.getCode())) {
            return;
        }
        if (Objects.equals(record.getStatus(), RELEASED.getCode())) {
            throw new IllegalStateException("库存已释放，不能再确认成交");
        }
        int recordRows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .in(StockDeductRecordEntity::getStatus, PRE_DEDUCTED.getCode(), ORDER_CREATED.getCode())// 已预扣、订单已创建
                        .set(StockDeductRecordEntity::getStatus, SOLD.getCode())// 库存已确认成交
        );
        if (recordRows != 1) {
            throw new IllegalStateException("库存预扣记录成交失败");
        }
        int stockRows = stockItemMapper.update(
                null,
                Wrappers.<StockItemEntity>lambdaUpdate()
                        .eq(StockItemEntity::getId, record.getStockItemId())
                        .ge(StockItemEntity::getLockedStock, record.getQuantity())
                        .setSql(
                                "locked_stock = locked_stock - {0}, " +
                                        "sold_stock = sold_stock + {0}, " +
                                        "version = version + 1",
                                record.getQuantity()
                        )
        );
        if (stockRows != 1) {
            throw new IllegalStateException("锁定库存转成交库存失败");
        }
    }

    private void releaseStock(StockDeductRecordEntity record) {
        if (Objects.equals(record.getStatus(), RELEASED.getCode())) {
            return;
        }
        if (Objects.equals(record.getStatus(), SOLD.getCode())) {
            throw new IllegalStateException("库存已经成交，不能再释放");
        }
        /*
         * 额度 -> 预扣记录 -> 库存。
         * 后续任何一步失败时，额度归还一起回滚。
         */
        reservationAdmissionService.releaseQuota(record);
        int recordRows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .in(StockDeductRecordEntity::getStatus, PRE_DEDUCTED.getCode(), ORDER_CREATED.getCode())// 已预扣、订单已创建
                        .set(StockDeductRecordEntity::getStatus,RELEASED.getCode())// 库存已释放
                        .set(StockDeductRecordEntity::getReleaseReason, "订单取消或超时")
        );
        if (recordRows != 1) {
            throw new IllegalStateException("库存预扣记录释放失败");
        }
        int stockRows = stockItemMapper.update(
                null,
                Wrappers.<StockItemEntity>lambdaUpdate()
                        .eq(StockItemEntity::getId, record.getStockItemId())
                        .ge(StockItemEntity::getLockedStock, record.getQuantity())
                        .setSql(
                                "available_stock = available_stock + {0}, " +
                                        "locked_stock = locked_stock - {0}, " +
                                        "version = version + 1",
                                record.getQuantity()
                        )
        );
        if (stockRows != 1) {
            throw new IllegalStateException("锁定库存释放失败");
        }
    }

    private void markConsumed(Long id) {
        int rows = mqConsumeLogMapper.update(
                null,
                Wrappers.<MqConsumeLogEntity>lambdaUpdate()
                        .eq(MqConsumeLogEntity::getId, id)
                        .eq(MqConsumeLogEntity::getStatus, 0)
                        .set(MqConsumeLogEntity::getStatus, 10)
        );
        if (rows != 1) {
            throw new IllegalStateException("结果消费日志更新失败");
        }
    }

    private void validateMessage(OrderStateChangedMessage message) {
        if (message == null
                || !StringUtils.hasText(message.getMessageId())
                || !StringUtils.hasText(message.getEventType())
                || !StringUtils.hasText(message.getOrderNo())
                || !StringUtils.hasText(message.getDeductNo())
                || message.getStockItemId() == null
                || message.getQuantity() == null
                || message.getQuantity() <= 0
                || message.getFromStatus() == null
                || message.getToStatus() == null) {
            throw new IllegalArgumentException("订单状态消息协议异常");
        }

        boolean validTransition =
                ORDER_CONFIRMED.equals(message.getEventType())
                        && Objects.equals(message.getFromStatus(), RESERVED.getCode())
                        && Objects.equals(message.getToStatus(), CONFIRMED.getCode())
                        || ORDER_CANCELLED.equals(message.getEventType())
                        && Objects.equals(message.getFromStatus(), RESERVED.getCode())
                        && Objects.equals(message.getToStatus(), CANCELLED.getCode())
                        || ORDER_TIMEOUT.equals(message.getEventType())
                        && Objects.equals(message.getFromStatus(), RESERVED.getCode())
                        && Objects.equals(message.getToStatus(), TIMEOUT.getCode());

        if (!validTransition) {
            throw new IllegalArgumentException("订单状态事件与状态流转不匹配");
        }
    }

    private void validateRecord(OrderStateChangedMessage message, StockDeductRecordEntity record) {
        if (!Objects.equals(record.getOrderNo(), message.getOrderNo())
                || !Objects.equals(record.getStockItemId(), message.getStockItemId())
                || !Objects.equals(record.getQuantity(), message.getQuantity())) {
            throw new IllegalArgumentException("订单状态消息与库存预扣记录不匹配");
        }
    }
}
