package com.javaup.resource.service;

import com.javaup.dto.InstantReservationResultDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.exception.InstantStockMismatchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
public class InstantReservationProcessor {

    private final ResourceOrderService resourceOrderService;
    private final ReservationRequestService requestService;
    private final InstantAdmissionService admissionService;
    private final int maxRetry;
    private final int retryDelaySeconds;

    public InstantReservationProcessor(
            ResourceOrderService resourceOrderService,
            ReservationRequestService requestService,
            InstantAdmissionService admissionService,
            @Value("${floworder.v8.max-retry:3}") int maxRetry,
            @Value("${floworder.v8.retry-delay-seconds:2}") int retryDelaySeconds
    ) {
        this.resourceOrderService = resourceOrderService;
        this.requestService = requestService;
        this.admissionService = admissionService;
        this.maxRetry = maxRetry;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public InstantReservationResultDto process(ReservationRequestEntity request, String owner) {
        ResourceOrderCreateDto dto = toCreateDto(request);
        String digest = admissionService.digest(dto);
        try {
            if (!admissionService.isHeld(dto, digest)) {// 校验Redis准入凭证还在
                return scheduleRetry(
                        request,
                        owner,
                        new IllegalStateException("Redis准入凭证不存在或已释放"),
                        "准入结果确认中"
                );
            }
            String orderNo = resourceOrderService.createInstantAfterAdmission(
                    dto,
                    request.getId(),
                    owner
            );
            requestService.markAccepted(request.getId(), owner, orderNo);// 请求 → ACCEPTED
            return accepted(dto.getRequestId(), orderNo);// 返回抢票成功
        } catch (InstantStockMismatchException exception) {
            return handleDefiniteFailure(request, owner, dto, digest, exception, true, "MYSQL_STOCK_REJECTED");
        } catch (BizException exception) {
            return handleDefiniteFailure(request, owner, dto, digest, exception, false, "BUSINESS_REJECTED");
        } catch (RuntimeException exception) {
            return handleTechnicalFailure(request, owner, exception);
        }
    }

    private InstantReservationResultDto handleDefiniteFailure(
            ReservationRequestEntity request,
            String owner,
            ResourceOrderCreateDto dto,
            String digest,
            RuntimeException exception,
            boolean invalidateStock,
            String reasonCode
    ) {
        try {
            admissionService.release(dto, digest, invalidateStock);
        } catch (RuntimeException releaseException) {
            exception.addSuppressed(releaseException);
            return scheduleRetry(request, owner, exception, "Redis补偿失败，等待恢复");
        }
        requestService.markFailed(request.getId(), owner, exception.getMessage());
        return rejected(dto.getRequestId(), reasonCode, exception.getMessage());
    }

    private InstantReservationResultDto handleTechnicalFailure(
            ReservationRequestEntity request,
            String owner,
            RuntimeException exception
    ) {
        ReservationRequestEntity current = requestService.findByRequestId(request.getRequestId());
        if (current != null
                && Objects.equals(current.getStatus(), ReservationRequestStatusEnum.ACCEPTED.getStatus())
                && current.getOrderNo() != null) {
            return accepted(current.getRequestId(), current.getOrderNo());
        }
        return scheduleRetry(request, owner, exception, "技术异常，结果确认中");
    }

    private InstantReservationResultDto scheduleRetry(
            ReservationRequestEntity request,
            String owner,
            RuntimeException exception,
            String message
    ) {
        int nextRetry = Objects.requireNonNullElse(request.getRetryCount(), 0) + 1;
        if (nextRetry >= maxRetry) {
            requestService.markManualReview(request.getId(), owner, exception.getMessage());
            log.error("Instant请求进入人工审核, requestId={}", request.getRequestId(), exception);
            return processing(request.getRequestId(), "MANUAL_REVIEW", "结果需要人工确认");
        }
        requestService.markRetry(
                request.getId(),
                owner,
                LocalDateTime.now().plusSeconds(retryDelaySeconds),
                exception.getMessage()
        );
        log.warn("Instant请求等待重试, requestId={}, retryCount={}", request.getRequestId(), nextRetry, exception);
        return processing(request.getRequestId(), "TECHNICAL_UNKNOWN", message);
    }

    private ResourceOrderCreateDto toCreateDto(ReservationRequestEntity request) {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setRequestId(request.getRequestId());
        dto.setUserId(request.getUserId());
        dto.setResourceId(request.getResourceId());
        dto.setStockItemId(request.getStockItemId());
        dto.setQuantity(request.getQuantity());
        return dto;
    }

    public static InstantReservationResultDto accepted(String requestId, String orderNo) {
        return result(requestId, orderNo, "ACCEPTED", "ACCEPTED", "抢票成功，订单生成中", false);
    }

    public static InstantReservationResultDto rejected(String requestId, String reasonCode, String message) {
        return result(requestId, null, "REJECTED", reasonCode, message, false);
    }

    public static InstantReservationResultDto processing(String requestId, String reasonCode, String message) {
        return result(requestId, null, "PROCESSING", reasonCode, message, true);
    }

    private static InstantReservationResultDto result(
            String requestId,
            String orderNo,
            String status,
            String reasonCode,
            String message,
            boolean queryRequired
    ) {
        InstantReservationResultDto result = new InstantReservationResultDto();
        result.setRequestId(requestId);
        result.setOrderNo(orderNo);
        result.setResultStatus(status);
        result.setReasonCode(reasonCode);
        result.setMessage(message);
        result.setQueryRequired(queryRequired);
        return result;
    }
}
