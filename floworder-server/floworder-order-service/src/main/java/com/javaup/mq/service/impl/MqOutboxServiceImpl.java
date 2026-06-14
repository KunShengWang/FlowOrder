package com.javaup.mq.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.javaup.constant.OrderMqConstant.ORDER_SERVICE;

@Service
public class MqOutboxServiceImpl implements MqOutboxService {

    private static final int STATUS_NEW = 0;
    private static final int STATUS_SENDING = 10;
    private static final int STATUS_RETRY = 30;

    private static final int STATUS_SENT = 20;
    private static final int STATUS_DEAD = 40;
    private static final int MAX_RETRY_COUNT = 5;

    @Resource
    private MqOutboxMapper mqOutboxMapper;

    /**
     * 查询可发生的消息
     */
    @Override
    public List<MqOutboxEntity> findSendable(int limit) {
        LocalDateTime now = LocalDateTime.now();

        return mqOutboxMapper.selectList(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .in(MqOutboxEntity::getStatus, STATUS_NEW, STATUS_RETRY)
                        .le(MqOutboxEntity::getNextRetryTime, now)
                        .orderByAsc(MqOutboxEntity::getId)
                        .last("limit " + limit)
        );
    }

    /**
     * 抢占消息
     */
    @Override
    public boolean claim(Long id) {
        LocalDateTime now = LocalDateTime.now();

        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .in(MqOutboxEntity::getStatus, STATUS_NEW, STATUS_RETRY)
                        .le(MqOutboxEntity::getNextRetryTime, now)
                        .set(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .set(MqOutboxEntity::getClaimUntil, now.plusSeconds(60))
        );

        return rows == 1;
    }

    @Override
    public void markSent(Long id) {
        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .set(MqOutboxEntity::getStatus, STATUS_SENT)
                        .set(MqOutboxEntity::getSentAt, LocalDateTime.now())
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, null)
        );

        if (rows != 1) {
            throw new BizException("更新Outbox发送成功状态失败");
        }
    }

    @Override
    public void markFailed(
            Long id,
            Integer currentRetryCount,
            String error) {

        int nextRetryCount =
                Objects.requireNonNullElse(currentRetryCount, 0) + 1;

        boolean dead = nextRetryCount >= MAX_RETRY_COUNT;

        int rows = mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getId, id)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .set(MqOutboxEntity::getStatus,
                                dead ? STATUS_DEAD : STATUS_RETRY)
                        .set(MqOutboxEntity::getRetryCount, nextRetryCount)
                        .set(MqOutboxEntity::getNextRetryTime,
                                dead ? null : calculateNextRetryTime(nextRetryCount))
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, limitError(error))
        );

        if (rows != 1) {
            throw new BizException("更新Outbox发送失败状态失败");
        }
    }

    @Override
    public int reclaimExpiredClaims() {
        LocalDateTime now = LocalDateTime.now();

        return mqOutboxMapper.update(
                null,
                Wrappers.<MqOutboxEntity>lambdaUpdate()
                        .eq(MqOutboxEntity::getProducerService, ORDER_SERVICE)
                        .eq(MqOutboxEntity::getStatus, STATUS_SENDING)
                        .le(MqOutboxEntity::getClaimUntil, now)
                        .set(MqOutboxEntity::getStatus, STATUS_RETRY)
                        .set(MqOutboxEntity::getNextRetryTime, now)
                        .set(MqOutboxEntity::getClaimUntil, null)
                        .set(MqOutboxEntity::getLastError, "发送租约过期，等待重新发送")
        );
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        long delaySeconds = switch (retryCount) {
            case 1 -> 5;
            case 2 -> 30;
            case 3 -> 120;
            default -> 300;
        };
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }

    private String limitError(String error) {
        if (!StringUtils.hasText(error)) {
            return "RabbitMQ消息发送失败";
        }
        return error.length() > 1024 ? error.substring(0, 1024) : error;
    }
}
