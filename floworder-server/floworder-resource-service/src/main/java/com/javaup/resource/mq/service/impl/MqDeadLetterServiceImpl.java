package com.javaup.resource.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.dto.OrderCreateResultMessage;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mq.service.MqDeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.MANUAL_REVIEW;
import static com.javaup.resource.enums.StockDeductStatusEnum.PRE_DEDUCTED;

import com.javaup.client.OrderMqAdminClient;
import com.javaup.common.ApiResponse;
import com.javaup.resource.mq.service.MqOutboxService;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

import static com.javaup.resource.enums.StockDeductStatusEnum.*;

@Service
@RequiredArgsConstructor
public class MqDeadLetterServiceImpl implements MqDeadLetterService {

    private static final int STATUS_PENDING = 0;
    private static final int CREATE_MODE_ASYNC = 3;
    private static final int STATUS_REPLAYING = 10;
    private static final int MAX_REPLAY_COUNT = 5;
    private static final int SUCCESS_CODE = 200;
    private static final int STATUS_RESOLVED = 20;
    private static final int STATUS_IGNORED = 30;

    private final MqOutboxService outboxService;
    private final OrderMqAdminClient orderMqAdminClient;
    private final TransactionTemplate transactionTemplate;

    private final MqDeadLetterMapper deadLetterMapper;
    private final MqOutboxMapper outboxMapper;
    private final StockDeductRecordMapper deductRecordMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String deadQueue,
                       String messageId,
                       String content,
                       String deathReason) {
        if (!StringUtils.hasText(deadQueue)
                || !StringUtils.hasText(messageId)
                || content == null) {
            throw new IllegalArgumentException("Dead-letter metadata is incomplete");
        }

        MqDeadLetterEntity entity = buildEntity(
                deadQueue,
                messageId,
                content,
                deathReason
        );

        try {
            deadLetterMapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            // The first delivery already completed the same transaction.
            return;
        }

        if (ORDER_CREATE_DLQ.equals(deadQueue) && StringUtils.hasText(entity.getBizKey())) {
            // 创建订单的错误，把状态改为人工确认
            isolateUncertainCreate(entity);
        }
    }

    @Override
    public List<MqDeadLetterAdminDto> find(Integer status, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return deadLetterMapper.selectList(
                        Wrappers.<MqDeadLetterEntity>lambdaQuery()
                                .eq(status != null, MqDeadLetterEntity::getStatus, status)
                                .orderByAsc(MqDeadLetterEntity::getCreatedAt)
                                .last("limit " + safeLimit)
                ).stream()
                .map(this::toAdminDto)
                .toList();
    }

    @Override
    public MqDeadLetterAdminDto findById(Long id) {
        MqDeadLetterEntity entity = deadLetterMapper.selectById(id);
        if (entity == null) {
            throw new BizException("Dead-letter record does not exist");
        }
        return toAdminDto(entity);
    }

    /**
     * 人工重放
     */
    @Override
    public void replay(Long id, String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new BizException("处理人不能为空");
        }

        MqDeadLetterEntity dead = requireDeadLetter(id);

        if (ORDER_CREATE_DLQ.equals(dead.getDeadQueue())) {
            // 数据库事务处理，明确事务边界与远程调用隔离
            transactionTemplate.executeWithoutResult(
                    status -> replayCreate(id, operator)
            );
            return;
        }

        if (!ORDER_RESULT_DLQ.equals(dead.getDeadQueue())
                && !ORDER_STATE_DLQ.equals(dead.getDeadQueue())) {
            throw new BizException("不支持的死信队列");
        }

        MqDeadLetterEntity claimed = transactionTemplate.execute(
                status -> claimReplay(id, operator)
        );

        try {
            // 结果、状态消息的原 Outbox 属于 order-service，因此 resource-service 通过 Feign 通知 order-service
            ApiResponse<Void> response =
                    orderMqAdminClient.replayConsumerDead(claimed.getMessageId());

            if (response == null || !Objects.equals(response.getCode(), SUCCESS_CODE)) {
                throw new BizException(response == null
                        ? "订单服务重放响应为空"
                        : "订单服务重放失败：" + response.getMessage());
            }
        } catch (RuntimeException exception) {
            markReplayFailed(id, exception.getMessage());
            throw exception;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveOrderResult(OrderCreateResultMessage message) {
        resolve(ORDER_RESULT_DLQ, message.getMessageId(), null,
                "订单结果消息消费完成");
        resolve(ORDER_CREATE_DLQ, null, message.getDeductNo(),
                "订单创建结果已确认");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveOrderState(OrderStateChangedMessage message) {
        resolve(ORDER_STATE_DLQ, message.getMessageId(), null,
                "订单状态消息消费完成");
        resolve(ORDER_CREATE_DLQ, null, message.getDeductNo(),
                "订单状态已经收敛");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ignore(Long id, String operator, String reason, boolean force) {
        if (!StringUtils.hasText(operator) || !StringUtils.hasText(reason)) {
            throw new BizException("处理人和忽略原因不能为空");
        }

        MqDeadLetterEntity dead = requireDeadLetter(id);

        if (!force && !isBusinessConverged(dead)) {
            throw new BizException("业务状态尚未收敛，不能忽略死信");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = deadLetterMapper.update(
                null,
                Wrappers.<MqDeadLetterEntity>lambdaUpdate()
                        .eq(MqDeadLetterEntity::getId, id)
                        .in(MqDeadLetterEntity::getStatus,
                                STATUS_PENDING, STATUS_REPLAYING)
                        .set(MqDeadLetterEntity::getStatus, STATUS_IGNORED)
                        .set(MqDeadLetterEntity::getHandledBy, limit(operator, 64))
                        .set(MqDeadLetterEntity::getResolutionNote, limit(reason, 512))
                        .set(MqDeadLetterEntity::getResolvedAt, now)
                        .set(MqDeadLetterEntity::getUpdatedAt, now)
        );

        if (rows != 1) {
            throw new BizException("死信状态已变化，无法忽略");
        }
    }

    @Override
    public int recoverStaleReplaying(LocalDateTime deadline, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);

        List<MqDeadLetterEntity> records = deadLetterMapper.selectList(
                Wrappers.<MqDeadLetterEntity>lambdaQuery()
                        .eq(MqDeadLetterEntity::getStatus, STATUS_REPLAYING)
                        .le(MqDeadLetterEntity::getReplayedAt, deadline)
                        .orderByAsc(MqDeadLetterEntity::getReplayedAt)
                        .last("limit " + safeLimit)
        );

        int recovered = 0;
        for (MqDeadLetterEntity record : records) {
            Boolean changed = transactionTemplate.execute(
                    status -> recoverOne(record.getId())
            );
            if (Boolean.TRUE.equals(changed)) {
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public long countUnresolved() {
        return deadLetterMapper.selectCount(
                Wrappers.<MqDeadLetterEntity>lambdaQuery()
                        .in(MqDeadLetterEntity::getStatus,
                                STATUS_PENDING, STATUS_REPLAYING)
        );
    }

    private boolean recoverOne(Long id) {
        MqDeadLetterEntity dead = deadLetterMapper.selectById(id);
        if (dead == null
                || !Objects.equals(dead.getStatus(), STATUS_REPLAYING)) {
            return false;
        }

        if (isBusinessConverged(dead)) {
            return resolve(dead.getDeadQueue(), dead.getMessageId(), null,
                    "扫描确认业务状态已经收敛") > 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = deadLetterMapper.update(
                null,
                Wrappers.<MqDeadLetterEntity>lambdaUpdate()
                        .eq(MqDeadLetterEntity::getId, id)
                        .eq(MqDeadLetterEntity::getStatus, STATUS_REPLAYING)// 重放中
                        .set(MqDeadLetterEntity::getStatus, STATUS_PENDING)// 待处理
                        .set(MqDeadLetterEntity::getLastError, "重放结果确认超时")
                        .set(MqDeadLetterEntity::getUpdatedAt, now)
        );

        if (rows == 1 && ORDER_CREATE_DLQ.equals(dead.getDeadQueue())) {
            deductRecordMapper.update(
                    null,
                    Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                            .eq(StockDeductRecordEntity::getDeductNo,
                                    dead.getBizKey())
                            .eq(StockDeductRecordEntity::getCreateMode,
                                    CREATE_MODE_ASYNC)
                            .eq(StockDeductRecordEntity::getStatus,
                                    PRE_DEDUCTED.getCode())// 预扣
                            .set(StockDeductRecordEntity::getStatus,
                                    MANUAL_REVIEW.getCode())// 人工确认
                            .set(StockDeductRecordEntity::getLastError,
                                    "订单创建死信重放确认超时")
            );
        }
        return rows == 1;
    }

    private int resolve(String queue, String messageId,
                        String bizKey, String note) {
        LocalDateTime now = LocalDateTime.now();
        return deadLetterMapper.update(
                null,
                Wrappers.<MqDeadLetterEntity>lambdaUpdate()
                        .eq(MqDeadLetterEntity::getDeadQueue, queue)
                        .eq(StringUtils.hasText(messageId),
                                MqDeadLetterEntity::getMessageId, messageId)
                        .eq(StringUtils.hasText(bizKey),
                                MqDeadLetterEntity::getBizKey, bizKey)
                        .in(MqDeadLetterEntity::getStatus, 0, 10)
                        .set(MqDeadLetterEntity::getStatus, 20)// 状态改为已解决
                        .set(MqDeadLetterEntity::getHandledBy, "SYSTEM")
                        .set(MqDeadLetterEntity::getResolutionNote, note)
                        .set(MqDeadLetterEntity::getResolvedAt, now)
                        .set(MqDeadLetterEntity::getUpdatedAt, now)
        );
    }

    private MqDeadLetterEntity buildEntity(String deadQueue,
                                           String messageId,
                                           String content,
                                           String deathReason) {
        MqDeadLetterEntity entity = new MqDeadLetterEntity();
        entity.setMessageId(messageId);
        entity.setDeadQueue(deadQueue);
        entity.setContent(content);
        entity.setDeathReason(limit(deathReason, 255));
        entity.setStatus(STATUS_PENDING);// 待处理
        entity.setReplayCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        if (ORDER_CREATE_DLQ.equals(deadQueue)) {
            fillCreateMetadata(entity);
        } else if (ORDER_RESULT_DLQ.equals(deadQueue)) {
            fillResultMetadata(entity);
        } else if (ORDER_STATE_DLQ.equals(deadQueue)) {
            fillStateMetadata(entity);
        } else {
            throw new IllegalArgumentException("Unsupported dead-letter queue: " + deadQueue);
        }
        return entity;
    }

    private void fillCreateMetadata(MqDeadLetterEntity entity) {
        entity.setProducerService(RESOURCE_SERVICE);
        entity.setExchangeName(ORDER_CREATE_EXCHANGE);
        entity.setRoutingKey(ORDER_CREATE_ROUTING_KEY);
        try {
            OrderCreateMessage message = objectMapper.readValue(
                    entity.getContent(),
                    OrderCreateMessage.class
            );
            entity.setMessageType(message.getEventType());
            if (message.getData() != null) {
                entity.setBizKey(message.getData().getDeductNo());
            }
            if (!StringUtils.hasText(entity.getMessageType())
                    || !StringUtils.hasText(entity.getBizKey())) {
                recoverCreateMetadataFromOutbox(entity);
            }
        } catch (JsonProcessingException exception) {
            entity.setLastError(limit("Create message cannot be parsed: "
                    + exception.getOriginalMessage(), 1024));
            recoverCreateMetadataFromOutbox(entity);
        }
    }

    private void recoverCreateMetadataFromOutbox(MqDeadLetterEntity entity) {
        MqOutboxEntity outbox = outboxMapper.selectOne(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getMessageId, entity.getMessageId())
                        .eq(MqOutboxEntity::getProducerService, RESOURCE_SERVICE)
                        .last("limit 1")
        );
        if (outbox != null) {
            entity.setMessageType(outbox.getMessageType());
            entity.setBizKey(outbox.getBizKey());
        }
    }

    private void fillResultMetadata(MqDeadLetterEntity entity) {
        entity.setProducerService(ORDER_SERVICE);
        entity.setExchangeName(ORDER_RESULT_EXCHANGE);
        entity.setRoutingKey(ORDER_RESULT_ROUTING_KEY);
        try {
            OrderCreateResultMessage message = objectMapper.readValue(
                    entity.getContent(),
                    OrderCreateResultMessage.class
            );
            entity.setMessageType(message.getEventType());
            entity.setBizKey(message.getDeductNo());
        } catch (JsonProcessingException exception) {
            entity.setLastError(limit("Result message cannot be parsed: "
                    + exception.getOriginalMessage(), 1024));
        }
    }

    private void fillStateMetadata(MqDeadLetterEntity entity) {
        entity.setProducerService(ORDER_SERVICE);
        entity.setExchangeName(ORDER_STATE_EXCHANGE);
        entity.setRoutingKey(ORDER_STATE_ROUTING_KEY);
        try {
            OrderStateChangedMessage message = objectMapper.readValue(
                    entity.getContent(),
                    OrderStateChangedMessage.class
            );
            entity.setMessageType(message.getEventType());
            entity.setBizKey(message.getDeductNo());
        } catch (JsonProcessingException exception) {
            entity.setLastError(limit("State message cannot be parsed: "
                    + exception.getOriginalMessage(), 1024));
        }
    }

    private void isolateUncertainCreate(MqDeadLetterEntity entity) {
        int rows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getDeductNo, entity.getBizKey())
                        .eq(StockDeductRecordEntity::getCreateMode, CREATE_MODE_ASYNC)
                        .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED.getCode())
                        .set(StockDeductRecordEntity::getStatus, MANUAL_REVIEW.getCode())
                        .set(StockDeductRecordEntity::getNextRetryTime, null)
                        .set(StockDeductRecordEntity::getLastError,
                                "Order create command entered DLQ: " + entity.getMessageId())
        );
        if (rows == 0) {
            StockDeductRecordEntity record = deductRecordMapper.selectOne(
                    Wrappers.<StockDeductRecordEntity>lambdaQuery()
                            .eq(StockDeductRecordEntity::getDeductNo, entity.getBizKey())
                            .last("limit 1")
            );
            if (record == null) {
                appendLastError(entity, "Stock deduction record was not found");
            }
        }
    }

    private void appendLastError(MqDeadLetterEntity entity, String error) {
        String combined = StringUtils.hasText(entity.getLastError())
                ? entity.getLastError() + "; " + error
                : error;
        entity.setLastError(limit(combined, 1024));
        entity.setUpdatedAt(LocalDateTime.now());
        deadLetterMapper.updateById(entity);
    }

    private MqDeadLetterAdminDto toAdminDto(MqDeadLetterEntity entity) {
        MqDeadLetterAdminDto dto = new MqDeadLetterAdminDto();
        dto.setId(entity.getId());
        dto.setMessageId(entity.getMessageId());
        dto.setDeadQueue(entity.getDeadQueue());
        dto.setProducerService(entity.getProducerService());
        dto.setMessageType(entity.getMessageType());
        dto.setBizKey(entity.getBizKey());
        dto.setExchangeName(entity.getExchangeName());
        dto.setRoutingKey(entity.getRoutingKey());
        dto.setContent(entity.getContent());
        dto.setDeathReason(entity.getDeathReason());
        dto.setStatus(entity.getStatus());
        dto.setReplayCount(entity.getReplayCount());
        dto.setLastError(entity.getLastError());
        dto.setReplayedAt(entity.getReplayedAt());
        dto.setResolvedAt(entity.getResolvedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setHandledBy(entity.getHandledBy());
        dto.setResolutionNote(entity.getResolutionNote());
        return dto;
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void replayCreate(Long id, String operator) {
        // 死信 PENDING -> REPLAYING
        MqDeadLetterEntity dead = claimReplay(id, operator);

        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, dead.getBizKey())
                        .last("limit 1")
        );

        if (record == null) {
            throw new BizException("库存预扣记录不存在");
        }

        if (Objects.equals(record.getStatus(), ORDER_CREATED.getCode())// 订单已创建
                || Objects.equals(record.getStatus(), RELEASED.getCode())// 库存已释放
                || Objects.equals(record.getStatus(), SOLD.getCode())) {// 库存已确认成交
            // 状态改为已解决
            resolve(ORDER_CREATE_DLQ, dead.getMessageId(), null, "业务状态已经收敛");
            return;
        }
        // 库存 MANUAL_REVIEW -> PRE_DEDUCTED
        if (Objects.equals(record.getStatus(), MANUAL_REVIEW.getCode())) {
            int rows = deductRecordMapper.update(
                    null,
                    Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                            .eq(StockDeductRecordEntity::getId, record.getId())
                            .eq(StockDeductRecordEntity::getStatus, MANUAL_REVIEW.getCode())
                            .set(StockDeductRecordEntity::getStatus, PRE_DEDUCTED.getCode())// 由人工确认改为已预扣
                            .set(StockDeductRecordEntity::getLastError, null)
            );
            if (rows != 1) {
                throw new BizException("恢复预扣状态失败");
            }
        } else if (!Objects.equals(record.getStatus(), PRE_DEDUCTED.getCode())) {
            throw new BizException("当前库存预扣状态不允许重放");
        }
        // 原创建 Outbox SENT/DEAD -> RETRY
        outboxService.replayConsumerDead(dead.getMessageId());
    }

    private MqDeadLetterEntity claimReplay(Long id, String operator) {
        MqDeadLetterEntity dead = requireDeadLetter(id);
        int count = Objects.requireNonNullElse(dead.getReplayCount(), 0);

        if (!Objects.equals(dead.getStatus(), STATUS_PENDING) || count >= MAX_REPLAY_COUNT) {
            throw new BizException("死信状态已变化或已达到最大重放次数");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = deadLetterMapper.update(null,
                Wrappers.<MqDeadLetterEntity>lambdaUpdate()
                        .eq(MqDeadLetterEntity::getId, id)
                        .eq(MqDeadLetterEntity::getStatus, STATUS_PENDING)// 待处理
                        .lt(MqDeadLetterEntity::getReplayCount, MAX_REPLAY_COUNT)
                        .set(MqDeadLetterEntity::getStatus, STATUS_REPLAYING)// 重放中
                        .setSql("replay_count = replay_count + 1")
                        .set(MqDeadLetterEntity::getHandledBy, limit(operator, 64))
                        .set(MqDeadLetterEntity::getReplayedAt, now)
                        .set(MqDeadLetterEntity::getLastError, null));

        if (rows != 1) {
            throw new BizException("死信已被其他线程处理");
        }
        return dead;
    }

    private MqDeadLetterEntity requireDeadLetter(Long id) {
        if (id == null) {
            throw new BizException("死信记录ID不能为空");
        }

        MqDeadLetterEntity dead = deadLetterMapper.selectById(id);
        if (dead == null) {
            throw new BizException("死信记录不存在");
        }
        return dead;
    }

    private void markReplayFailed(Long id, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();

            deadLetterMapper.update(
                    null,
                    Wrappers.<MqDeadLetterEntity>lambdaUpdate()
                            .eq(MqDeadLetterEntity::getId, id)
                            .eq(MqDeadLetterEntity::getStatus, STATUS_REPLAYING)
                            .set(MqDeadLetterEntity::getStatus, STATUS_PENDING)
                            .set(MqDeadLetterEntity::getLastError,
                                    limit(StringUtils.hasText(error)
                                            ? error
                                            : "远程重放失败", 1024))
                            .set(MqDeadLetterEntity::getUpdatedAt, now)
            );
        });
    }

    private boolean isBusinessConverged(MqDeadLetterEntity dead) {
        if (!StringUtils.hasText(dead.getBizKey())) {
            return false;
        }

        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, dead.getBizKey())
                        .last("limit 1")
        );
        if (record == null) {
            return false;
        }

        Integer status = record.getStatus();
        // 如果是订单已创建、库存已释放、库存已确认成交中的其中一个就返回true
        if (ORDER_CREATE_DLQ.equals(dead.getDeadQueue())) {
            return Objects.equals(status, ORDER_CREATED.getCode())
                    || Objects.equals(status, RELEASED.getCode())
                    || Objects.equals(status, SOLD.getCode());
        }

        try {
            if (ORDER_RESULT_DLQ.equals(dead.getDeadQueue())) {
                OrderCreateResultMessage message = objectMapper.readValue(
                        dead.getContent(), OrderCreateResultMessage.class);

                return Boolean.TRUE.equals(message.getSuccess())
                        ? Objects.equals(status, ORDER_CREATED.getCode())
                        || Objects.equals(status, RELEASED.getCode())
                        || Objects.equals(status, SOLD.getCode())
                        : Objects.equals(status, RELEASED.getCode());
            }

            if (ORDER_STATE_DLQ.equals(dead.getDeadQueue())) {
                OrderStateChangedMessage message = objectMapper.readValue(
                        dead.getContent(),
                        OrderStateChangedMessage.class
                );

                String eventType = message.getEventType();

                if (ORDER_CONFIRMED.equals(eventType)) {
                    return Objects.equals(status, SOLD.getCode());
                }

                if (ORDER_CANCELLED.equals(eventType)
                        || ORDER_TIMEOUT.equals(eventType)) {
                    return Objects.equals(status, RELEASED.getCode());
                }

                // 未知、为空或未来新增但尚未支持的事件，不能视为业务已收敛
                return false;
            }
        } catch (JsonProcessingException ignored) {
            return false;
        }

        return false;
    }
}
