package com.javaup.resource.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.resource.entity.MqConsumeLogEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.MqConsumeLogMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mq.service.OrderResultMessageService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.*;

@Service
public class OrderResultMessageServiceImpl implements OrderResultMessageService {

    @Resource
    private MqConsumeLogMapper consumeLogMapper;

    @Resource
    private StockDeductRecordMapper stockDeductRecordMapper;

    @Resource
    private StockItemMapper stockItemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long handle(OrderCreateResultMessage message) {
        validateBasicMessage(message);
        StockDeductRecordEntity record = findRecord(message.getDeductNo());
        validateMessage(message, record);
        MqConsumeLogEntity log = insertConsumeLog(message);

        if (log == null) {
            return Boolean.FALSE.equals(message.getSuccess())
                    ? record.getStockItemId() : null;
        }

        if (Boolean.TRUE.equals(message.getSuccess())) {
            confirm(record, message.getOrderNo());
        } else {
            release(record, message.getErrorMessage());
        }

        markConsumed(log.getId());
        return Boolean.FALSE.equals(message.getSuccess())
                ? record.getStockItemId()
                : null;
    }

    private void confirm(StockDeductRecordEntity record, String orderNo) {
        if (Objects.equals(record.getStatus(), ORDER_CREATED.getCode()) && Objects.equals(record.getOrderNo(), orderNo)) {
            return;
        }
        /*
         * 状态消息已经先到达：
         * RELEASED 表示订单已取消或超时；
         * SOLD 表示订单已经确认。
         *
         * 创建成功消息此时属于迟到消息，不能把终态改回 ORDER_CREATED。
         */
        if (Objects.equals(record.getStatus(), RELEASED.getCode()) || Objects.equals(record.getStatus(), SOLD.getCode())) {
            return;
        }
        int rows = stockDeductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .eq(StockDeductRecordEntity::getCreateMode, 3)
                        .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED.getCode())
                        .set(StockDeductRecordEntity::getStatus, ORDER_CREATED.getCode())
                        .set(StockDeductRecordEntity::getOrderNo, orderNo)
        );
        if (rows != 1) {
            throw new IllegalStateException("异步订单库存确认失败");
        }
    }

    private void release(StockDeductRecordEntity record, String reason) {
        if (Objects.equals(record.getStatus(), 30)) {
            return;
        }
        int recordRows = stockDeductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .eq(StockDeductRecordEntity::getCreateMode, 3)
                        .eq(StockDeductRecordEntity::getStatus, 10)
                        .set(StockDeductRecordEntity::getStatus, 30)
                        .set(StockDeductRecordEntity::getReleaseReason, limitReason(reason))
        );
        if (recordRows != 1) {
            throw new IllegalStateException("异步库存记录释放失败");
        }
        int stockRows = stockItemMapper.update(
                null,
                Wrappers.<StockItemEntity>lambdaUpdate()
                        .eq(StockItemEntity::getId, record.getStockItemId())
                        .ge(StockItemEntity::getLockedStock,
                                record.getQuantity())
                        .setSql(
                                "available_stock = available_stock + {0}, " +
                                        "locked_stock = locked_stock - {0}, " +
                                        "version = version + 1",
                                record.getQuantity()
                        )
        );
        if (stockRows != 1) {
            throw new IllegalStateException("异步订单失败后库存恢复失败");
        }
    }

    private StockDeductRecordEntity findRecord(String deductNo) {
        StockDeductRecordEntity record =
                stockDeductRecordMapper.selectOne(
                        Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                .eq(StockDeductRecordEntity::getDeductNo, deductNo)
                                .last("limit 1")
                );

        if (record == null
                || !Objects.equals(record.getCreateMode(), 3)) {
            throw new IllegalStateException("异步库存预扣记录不存在");
        }
        return record;
    }

    private MqConsumeLogEntity insertConsumeLog(OrderCreateResultMessage message) {
        MqConsumeLogEntity log = new MqConsumeLogEntity();
        log.setMessageId(message.getMessageId());
        log.setConsumerGroup(ORDER_RESULT_CONSUMER);
        log.setMessageType(message.getEventType());
        log.setBizKey(message.getDeductNo());
        log.setStatus(0);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());

        try {
            consumeLogMapper.insert(log);
            return log;
        } catch (DuplicateKeyException exception) {
            MqConsumeLogEntity existing = consumeLogMapper.selectOne(
                    Wrappers.<MqConsumeLogEntity>lambdaQuery()
                            .eq(MqConsumeLogEntity::getMessageId,
                                    message.getMessageId())
                            .eq(MqConsumeLogEntity::getConsumerGroup,
                                    ORDER_RESULT_CONSUMER)
                            .last("limit 1")
            );

            if (existing != null && Objects.equals(existing.getStatus(), 10)) {
                return null;
            }
            throw exception;
        }
    }

    private void markConsumed(Long id) {
        int rows = consumeLogMapper.update(
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

    private String limitReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "异步订单创建失败";
        }
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    private void validateBasicMessage(OrderCreateResultMessage message) {
        if (message == null
                || !StringUtils.hasText(message.getMessageId())
                || !StringUtils.hasText(message.getEventType())
                || !StringUtils.hasText(message.getRequestId())
                || !StringUtils.hasText(message.getDeductNo())
                || message.getSuccess() == null) {
            throw new IllegalArgumentException("订单结果消息协议异常");
        }
    }

    private void validateMessage(OrderCreateResultMessage message, StockDeductRecordEntity record) {

        if (message == null
                || !StringUtils.hasText(message.getMessageId())
                || !StringUtils.hasText(message.getDeductNo())
                || message.getSuccess() == null) {
            throw new IllegalArgumentException("订单结果消息协议异常");
        }

        if (!Objects.equals(record.getRequestId(), message.getRequestId())) {
            throw new IllegalArgumentException("订单结果requestId不匹配");
        }

        if (Boolean.TRUE.equals(message.getSuccess())) {
            if (!ORDER_CREATE_SUCCEEDED.equals(message.getEventType())
                    || !StringUtils.hasText(message.getOrderNo())
                    || !Objects.equals(record.getOrderNo(), message.getOrderNo())) {
                throw new IllegalArgumentException("订单成功结果协议异常");
            }
        } else if (!ORDER_CREATE_FAILED.equals(message.getEventType())) {
            throw new IllegalArgumentException("订单失败结果协议异常");
        }
    }
}
