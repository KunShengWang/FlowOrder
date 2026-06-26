package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID;

@Slf4j
@Service
public class ReservationRequestProcessor {

    private final ResourceOrderService resourceOrderService;
    private final ReservationRequestService requestService;
    private final int maxRetry;
    private final int retryDelaySeconds;

    private final V8ReadValidationService readValidationService;

    public ReservationRequestProcessor(
            ResourceOrderService resourceOrderService,
            ReservationRequestService requestService,
            @Value("${floworder.v8.max-retry:3}")
            int maxRetry,
            @Value("${floworder.v8.retry-delay-seconds:2}")
            int retryDelaySeconds,
            V8ReadValidationService readValidationService
    ) {
        this.resourceOrderService = resourceOrderService;
        this.requestService = requestService;
        this.maxRetry = maxRetry;
        this.retryDelaySeconds = retryDelaySeconds;
        this.readValidationService = readValidationService;
    }

    public void process(ReservationRequestEntity request, String owner) {
        bindMdc(request);
        try {
            /*
             * 不在这里再次执行ReservationAdmissionService.check()。
             *
             * 如果上一次已经完成V3预扣但进程在更新V8状态前崩溃，
             * 再执行前置额度校验会把自己的已占用额度判断为超限。
             *
             * createV3首先按requestId检查已有预扣记录，
             * 因而可以安全恢复。
             */
            ResourceOrderCreateDto createDto =
                    toCreateDto(request);

            /*
             * 只在第一次执行时进行并行只读预检。
             *
             * 如果V3事务已经成功但V8状态更新前进程崩溃，
             * 租约恢复会增加retryCount。
             * 重试时跳过预检，直接依靠requestId幂等恢复。
             */
            if (request.getRetryCount() == null
                    || request.getRetryCount() == 0) {
                readValidationService.validate(createDto);
            }

            String orderNo =
                    resourceOrderService.createV3(createDto);
            requestService.markSucceeded(request.getId(), owner, orderNo);
            log.info(
                    "V8预约请求处理成功, requestId={}, orderNo={}",
                    request.getRequestId(),
                    orderNo
            );
        } catch (BizException exception) {
            /*
             * V8当前把BizException视为明确业务失败：
             * 窗口失效、资格无效、额度不足、库存不足等。
             */
            requestService.markFailed(request.getId(), owner, exception.getMessage());
            log.warn(
                    "V8预约请求业务失败, requestId={}, reason={}",
                    request.getRequestId(),
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            handleTechnicalFailure(request, owner, exception);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private void handleTechnicalFailure(ReservationRequestEntity request, String owner, RuntimeException exception) {
        int nextRetryCount = request.getRetryCount() == null ? 1 : request.getRetryCount() + 1;
        if (nextRetryCount >= maxRetry) {
            log.error(
                    "V8预约请求达到重试上限, requestId={}, retryCount={}",
                    request.getRequestId(),
                    nextRetryCount,
                    exception
            );
            requestService.markManualReview(request.getId(), owner, exception.getMessage());
            return;
        }
        requestService.markRetry(request.getId(), owner, LocalDateTime.now().plusSeconds(retryDelaySeconds), exception.getMessage());
        log.warn(
                "V8预约请求技术异常，等待重试, requestId={}, retryCount={}",
                request.getRequestId(),
                nextRetryCount,
                exception
        );
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

    private void bindMdc(ReservationRequestEntity request) {
        if (request.getTraceId() != null) {
            MDC.put(TRACE_ID, request.getTraceId());
        }
        if (request.getRequestId() != null) {
            MDC.put(REQUEST_ID, request.getRequestId());
        }
    }
}