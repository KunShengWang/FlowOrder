package com.javaup.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.MqOutboxAdminDto;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.javaup.constant.OrderMqConstant.ORDER_SERVICE;

@Service
public class MqOutboxServiceImpl implements MqOutboxService {

    private static final int STATUS_NEW = 0;
    private static final int STATUS_SENDING = 10;
    private static final int STATUS_RETRY = 30;

    private static final int STATUS_SENT = 20;
    private static final int STATUS_DEAD = 40;
    @Resource
    private MqOutboxMapper mqOutboxMapper;

    @Value("${floworder.mq.outbox.max-retry:5}")
    private int maxRetryCount;

    @Value("${floworder.mq.outbox.retry-base-ms:5000}")
    private long retryBaseMillis;

    @Value("${floworder.mq.outbox.retry-max-ms:300000}")
    private long retryMaxMillis;

    @Value("${floworder.mq.outbox.retry-jitter-ms:1000}")
    private long retryJitterMillis;

    /**
     * 查询可发生的消息
     */
    @Override
    public List<MqOutboxEntity> findSendable(int limit) {
        LocalDateTime now = LocalDateTime.now();

        return mqOutboxMapper.selectList(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .in(MqOutboxEntity::getStatus, STATUS_NEW, STATUS_RETRY)// 待发送、待重试
                        .le(MqOutboxEntity::getNextRetryTime, now)
                        .orderByAsc(MqOutboxEntity::getId)
                        .last("limit " + limit)
        );
    }

    /**
     * 抢占消息
     */
    @Override
    public String claim(Long id, String claimOwner, long leaseSeconds) {
        String claimToken = UUID.randomUUID().toString();
        int rows = mqOutboxMapper.claim(
                id,
                ORDER_SERVICE,
                claimOwner,
                claimToken,
                Math.max(1, leaseSeconds)
        );
        return rows == 1 ? claimToken : null;
    }

    @Override
    public boolean markSent(Long id, String claimToken) {
        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .eq(MqOutboxEntity::getClaimToken, claimToken)
                        .set(MqOutboxEntity::getStatus, STATUS_SENT)
                        .set(MqOutboxEntity::getSentAt, LocalDateTime.now())
                        .set(MqOutboxEntity::getClaimOwner, null)
                        .set(MqOutboxEntity::getClaimToken, null)
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, null)
        );

        return rows == 1;
    }

    @Override
    public boolean markFailed(
            Long id,
            String claimToken,
            Integer currentRetryCount,
            String error) {

        int nextRetryCount =
                Objects.requireNonNullElse(currentRetryCount, 0) + 1;

        boolean dead = nextRetryCount >= Math.max(1, maxRetryCount);

        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .eq(MqOutboxEntity::getClaimToken, claimToken)
                        .set(MqOutboxEntity::getStatus,
                                dead ? STATUS_DEAD : STATUS_RETRY)
                        .set(MqOutboxEntity::getRetryCount, nextRetryCount)
                        .set(MqOutboxEntity::getNextRetryTime,
                                dead ? null : calculateNextRetryTime(nextRetryCount))
                        .set(MqOutboxEntity::getClaimOwner, null)
                        .set(MqOutboxEntity::getClaimToken, null)
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, limitError(error))
        );

        return rows == 1;
    }

    @Override
    public boolean releaseClaim(Long id, String claimToken, long delayMillis, String error) {
        LocalDateTime nextRetryTime = LocalDateTime.now()
                .plusNanos(Math.max(1, delayMillis) * 1_000_000L);
        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .eq(MqOutboxEntity::getClaimToken, claimToken)
                        .set(MqOutboxEntity::getStatus, STATUS_RETRY)
                        .set(MqOutboxEntity::getNextRetryTime, nextRetryTime)
                        .set(MqOutboxEntity::getClaimOwner, null)
                        .set(MqOutboxEntity::getClaimToken, null)
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, limitError(error))
        );
        return rows == 1;
    }

    @Override
    public int reclaimExpiredClaims(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<MqOutboxEntity> expired = mqOutboxMapper.selectList(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .isNotNull(MqOutboxEntity::getClaimToken)
                        .le(MqOutboxEntity::getClaimUntil, now)
                        .orderByAsc(MqOutboxEntity::getClaimUntil)
                        .last("limit " + Math.min(Math.max(limit, 1), 500))
        );
        int reclaimed = 0;
        for (MqOutboxEntity record : expired) {
            int rows = mqOutboxMapper.reclaimExpired(
                    record.getId(), ORDER_SERVICE, record.getClaimToken());
            reclaimed += rows;
        }
        return reclaimed;
    }

    @Override
    public List<MqOutboxAdminDto> findDead(int limit) {
        int queryLimit = Math.min(Math.max(limit, 1), 500);

        return mqOutboxMapper.selectList(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_DEAD)
                        .orderByDesc(MqOutboxEntity::getUpdatedAt)
                        .last("limit " + queryLimit)
        ).stream().map(this::toAdminDto).toList();
    }

    @Override
    public void retryDead(String messageId) {
        resetOutbox(messageId, STATUS_DEAD, "人工恢复死亡消息");
    }

    @Override
    public void replaySent(String messageId) {
        resetOutbox(messageId, STATUS_SENT, "人工重放已发送消息");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replayConsumerDead(String messageId) {
        MqOutboxEntity outbox = mqOutboxMapper.selectOne(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getMessageId, messageId)
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .last("limit 1")
        );

        if (outbox == null) {
            throw new BizException("原始Outbox消息不存在");
        }

        if (Objects.equals(outbox.getStatus(), STATUS_SENT)) {
            resetOutbox(messageId, STATUS_SENT, "消费死信人工重放");
            return;
        }

        if (Objects.equals(outbox.getStatus(), STATUS_DEAD)) {
            resetOutbox(messageId, STATUS_DEAD, "消费死信恢复发送");
            return;
        }

        if (Objects.equals(outbox.getStatus(), STATUS_NEW)
                || Objects.equals(outbox.getStatus(), STATUS_SENDING)
                || Objects.equals(outbox.getStatus(), STATUS_RETRY)) {
            return;
        }

        throw new BizException("当前Outbox状态不允许重放");
    }

    private void resetOutbox(String messageId, int expectedStatus, String reason) {
        if (!StringUtils.hasText(messageId)) {
            throw new BizException("messageId不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getMessageId, messageId)
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, expectedStatus)
                        .set(MqOutboxEntity::getStatus, STATUS_RETRY)
                        .set(MqOutboxEntity::getRetryCount, 0)
                        .set(MqOutboxEntity::getNextRetryTime, now)
                        .set(MqOutboxEntity::getClaimOwner, null)
                        .set(MqOutboxEntity::getClaimToken, null)
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getSentAt, null)
                        .set(MqOutboxEntity::getLastError, reason)
                        .set(MqOutboxEntity::getUpdatedAt, now)
        );
        if (rows != 1) {
            throw new BizException("Outbox不存在、状态已变化或不属于当前服务");
        }
    }

    private MqOutboxAdminDto toAdminDto(MqOutboxEntity entity) {
        MqOutboxAdminDto dto = new MqOutboxAdminDto();
        dto.setMessageId(entity.getMessageId());
        dto.setProducerService(entity.getProducerService());
        dto.setBizKey(entity.getBizKey());
        dto.setMessageType(entity.getMessageType());
        dto.setStatus(entity.getStatus());
        dto.setRetryCount(entity.getRetryCount());
        dto.setLastError(entity.getLastError());
        dto.setNextRetryTime(entity.getNextRetryTime());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        long max = Math.max(1, retryMaxMillis);
        long base = Math.max(1, retryBaseMillis);
        long multiplier = 1L << Math.min(Math.max(retryCount - 1, 0), 20);
        long exponential = base > max / multiplier ? max : base * multiplier;
        long jitterBound = Math.max(0, retryJitterMillis);
        long jitter = jitterBound == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return LocalDateTime.now().plusNanos((Math.min(max, exponential) + jitter) * 1_000_000L);
    }

    private String limitError(String error) {
        if (!StringUtils.hasText(error)) {
            return "RabbitMQ消息发送失败";
        }
        return error.length() > 1024 ? error.substring(0, 1024) : error;
    }
}
