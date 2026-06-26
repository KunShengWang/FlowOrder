package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.ReservationRequestResultDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.service.ReservationRequestService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class ReservationRequestServiceImpl implements ReservationRequestService {

    private final ReservationRequestMapper requestMapper;

    public ReservationRequestServiceImpl(ReservationRequestMapper requestMapper) {
        this.requestMapper = requestMapper;
    }

    @Override
    public String submit(ResourceOrderCreateDto dto, String traceId) {
        // 验证ResourceOrderCreateDto参数是否合法
        validateRequest(dto);
        ReservationRequestEntity existing = findByRequestId(dto.getRequestId());
        if (existing != null) {
            validateSameRequest(existing, dto);
            return existing.getRequestId();
        }
        ReservationRequestEntity request = buildRequest(dto, traceId);
        try {
            requestMapper.insert(request);
            return request.getRequestId();
        } catch (DuplicateKeyException exception) {
            /*
             * 两个线程同时提交相同requestId时，
             * 唯一索引负责最终并发幂等。
             */
            existing = findByRequestId(dto.getRequestId());
            if (existing == null) {
                throw exception;
            }
            validateSameRequest(existing, dto);
            return existing.getRequestId();
        }
    }

    @Override
    public List<ReservationRequestEntity> findClaimable(LocalDateTime now, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return requestMapper.findClaimable(now, safeLimit);
    }

    @Override
    public boolean claim(Long id, String owner, LocalDateTime now, LocalDateTime claimUntil) {
        if (id == null
                || !StringUtils.hasText(owner)
                || now == null
                || claimUntil == null
                || !claimUntil.isAfter(now)) {
            throw new IllegalArgumentException("租约参数非法");
        }
        return requestMapper.claim(id, owner, now, claimUntil) == 1;
    }

    @Override
    public void markSucceeded(
            Long id,
            String owner,
            String orderNo
    ) {
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("orderNo不能为空");
        }

        int rows = requestMapper.markSucceeded(
                id,
                owner,
                orderNo,
                LocalDateTime.now()
        );

        ensureUpdated(rows, "预约请求成功状态更新失败");
    }

    @Override
    public void markRetry(Long id, String owner, LocalDateTime nextRetryTime, String error) {
        int rows = requestMapper.markRetry(id, owner, nextRetryTime, limitError(error));
        ensureUpdated(rows, "预约请求重试状态更新失败");
    }

    @Override
    public void markOrderStateChanged(
            String requestId,
            String orderNo,
            Integer fromStatus,
            Integer toStatus,
            String eventType,
            LocalDateTime occurredAt
    ) {
        if (!StringUtils.hasText(requestId)
                || !StringUtils.hasText(orderNo)
                || fromStatus == null
                || toStatus == null
                || !StringUtils.hasText(eventType)) {
            return;
        }
        int rows = requestMapper.markOrderStateChanged(
                requestId,
                orderNo,
                fromStatus,
                toStatus,
                eventType,
                occurredAt,
                LocalDateTime.now()
        );
        if (rows != 1) {
            throw new IllegalStateException("预约请求订单状态更新失败");
        }
    }

    @Override
    public void markFailed(
            Long id,
            String owner,
            String error
    ) {
        int rows = requestMapper.markFailed(
                id,
                owner,
                limitError(error),
                LocalDateTime.now()
        );

        ensureUpdated(rows, "预约请求失败状态更新失败");
    }

    @Override
    public void markManualReview(Long id, String owner, String error) {
        int rows = requestMapper.markManualReview(id, owner, limitError(error), LocalDateTime.now());
        ensureUpdated(rows, "预约请求人工审核状态更新失败");
    }

    @Override
    public void releaseClaim(
            Long id,
            String owner,
            LocalDateTime nextRetryTime,
            String error
    ) {
        int rows = requestMapper.releaseClaim(
                id,
                owner,
                nextRetryTime,
                limitError(error)
        );

        ensureUpdated(rows, "预约请求租约释放失败");
    }

    @Override
    public int recoverExpired(LocalDateTime now, int limit, int maxRetry) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeMaxRetry = Math.max(maxRetry, 1);
        return requestMapper.recoverExpired(now, safeLimit, safeMaxRetry);
    }

    @Override
    public ReservationRequestResultDto getResult(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            throw new BizException("requestId不能为空");
        }
        ReservationRequestEntity request = findByRequestId(requestId);
        if (request == null) {
            throw new BizException("预约请求不存在");
        }
        ReservationRequestResultDto result = new ReservationRequestResultDto();
        result.setRequestId(request.getRequestId());
        result.setStatus(request.getStatus());
        result.setOrderNo(request.getOrderNo());
        result.setOrderStatus(request.getOrderStatus());
        result.setLatestOrderEventType(request.getLatestOrderEventType());
        result.setLatestOrderEventTime(request.getLatestOrderEventTime());
        result.setOrderEventVersion(request.getOrderEventVersion());
        result.setRetryCount(request.getRetryCount());
        result.setLastError(request.getLastError());
        result.setCreatedAt(request.getCreatedAt());
        result.setStartedAt(request.getStartedAt());
        result.setFinishedAt(request.getFinishedAt());
        return result;
    }

    private ReservationRequestEntity findByRequestId(String requestId) {
        return requestMapper.selectOne(
                Wrappers.<ReservationRequestEntity>lambdaQuery()
                        .eq(ReservationRequestEntity::getRequestId, requestId)
        );
    }

    private ReservationRequestEntity buildRequest(ResourceOrderCreateDto dto, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setRequestId(dto.getRequestId());
        request.setTraceId(traceId);
        request.setUserId(dto.getUserId());
        request.setResourceId(dto.getResourceId());
        request.setStockItemId(dto.getStockItemId());
        request.setQuantity(dto.getQuantity());
        request.setStatus(ReservationRequestStatusEnum.PENDING.getStatus());
        request.setOrderEventVersion(0);
        request.setRetryCount(0);
        request.setNextRetryTime(now);
        request.setVersion(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        return request;
    }

    private void validateRequest(ResourceOrderCreateDto dto) {
        if (dto == null
                || dto.getUserId() == null
                || dto.getResourceId() == null
                || dto.getStockItemId() == null
                || dto.getQuantity() == null
                || dto.getQuantity() <= 0
                || !StringUtils.hasText(dto.getRequestId())) {
            throw new BizException("预约请求参数非法");
        }
    }

    private void validateSameRequest(ReservationRequestEntity existing, ResourceOrderCreateDto dto) {
        boolean same = Objects.equals(existing.getUserId(), dto.getUserId())
                        && Objects.equals(existing.getResourceId(), dto.getResourceId())
                        && Objects.equals(existing.getStockItemId(), dto.getStockItemId())
                        && Objects.equals(existing.getQuantity(), dto.getQuantity());
        if (!same) {
            throw new BizException("相同requestId对应的预约参数不一致");
        }
    }

    private void ensureUpdated(int rows, String message) {
        if (rows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private String limitError(String error) {
        if (!StringUtils.hasText(error)) {
            return "unknown error";
        }
        return error.length() <= 1024
                ? error
                : error.substring(0, 1024);
    }
}
