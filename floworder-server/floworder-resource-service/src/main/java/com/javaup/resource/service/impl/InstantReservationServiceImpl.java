package com.javaup.resource.service.impl;

import com.javaup.dto.InstantReservationResultDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import com.javaup.resource.enums.ReservationProcessingModeEnum;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.service.InstantAdmissionService;
import com.javaup.resource.service.InstantReservationProcessor;
import com.javaup.resource.service.InstantReservationService;
import com.javaup.resource.service.ReservationRequestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class InstantReservationServiceImpl implements InstantReservationService {

    private final InstantAdmissionService admissionService;
    private final ReservationRequestService requestService;
    private final InstantReservationProcessor processor;
    private final int leaseSeconds;

    public InstantReservationServiceImpl(
            InstantAdmissionService admissionService,
            ReservationRequestService requestService,
            InstantReservationProcessor processor,
            @Value("${floworder.v8.lease-seconds:30}") int leaseSeconds
    ) {
        this.admissionService = admissionService;
        this.requestService = requestService;
        this.processor = processor;
        this.leaseSeconds = Math.max(1, leaseSeconds);
    }

    @Override
    public InstantReservationResultDto submit(ResourceOrderCreateDto dto, String traceId) {
        validate(dto);
        // Redis Lua 即时准入
        InstantAdmissionService.AdmissionAttempt attempt = admissionService.admit(dto);

        // 根据 lua 脚本的执行结果执行下一步的判断
        InstantAdmissionResultEnum admission = attempt.result();
        if (admission == InstantAdmissionResultEnum.SOLD_OUT) {
            return InstantReservationProcessor.rejected(dto.getRequestId(), "SOLD_OUT", "库存不足");
        }
        if (admission == InstantAdmissionResultEnum.IDEMPOTENT_CONFLICT) {
            return InstantReservationProcessor.rejected(
                    dto.getRequestId(),
                    "IDEMPOTENT_CONFLICT",
                    "相同requestId对应的预约参数不一致"
            );
        }
        if (admission == InstantAdmissionResultEnum.DUPLICATE_RELEASED) {
            ReservationRequestEntity existing = requestService.findByRequestId(dto.getRequestId());
            return existing == null
                    ? InstantReservationProcessor.rejected(dto.getRequestId(), "PREVIOUSLY_RELEASED", "该requestId对应的准入已释放")
                    : mapExisting(existing);
        }
        if (admission != InstantAdmissionResultEnum.ADMITTED_NEW
                && admission != InstantAdmissionResultEnum.ADMITTED_DUPLICATE) {
            throw new BizException("Instant准入失败：" + admission.name());
        }

        // 持久化预约请求
        ReservationRequestService.InstantRequestSubmission submission;
        try {
            submission = requestService.submitInstant(dto, traceId);
        } catch (BizException exception) {// 明确失败 → 释放 Redis + 返回 REJECTED
            releaseNewAdmissionAfterMysqlDuplicate(dto, attempt, admission, exception);
            return InstantReservationProcessor.rejected(
                    dto.getRequestId(),
                    "IDEMPOTENT_CONFLICT",
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {//  结果未知 → 不释放 Redis + 返回 PROCESSING/继续
            ReservationRequestEntity request = requestService.findByRequestId(dto.getRequestId());
            if (request == null) {
                return InstantReservationProcessor.processing(
                        dto.getRequestId(),
                        "PERSISTENCE_UNKNOWN",
                        "请求持久化结果确认中，请勿重复提交"
                );
            }
            // 插入调用抛异常后又查到行，既可能是并发旧行，也可能是本次提交成功但响应未知。
            // 这里保留UNKNOWN，不能据此释放Redis。
            submission = new ReservationRequestService.InstantRequestSubmission(request, null);
        }
        ReservationRequestEntity request = submission.request();

        // 没有持久化预约请求 + Redis 是新扣的 → 释放重复准入
        if (Boolean.FALSE.equals(submission.created())
                && admission == InstantAdmissionResultEnum.ADMITTED_NEW) {
            try {
                // 释放 redis 库存
                admissionService.release(dto, attempt.digest(), false);
            } catch (RuntimeException exception) {
                return InstantReservationProcessor.processing(
                        dto.getRequestId(),
                        "DUPLICATE_ADMISSION_RECOVERY",
                        "重复准入库存回收中，请查询原请求结果"
                );
            }
            return mapExisting(request);
        }
        // 请求已确认落库 → best-effort 把 requestId 从 INSTANT_UNPERSISTED ZSet 移除（标记"已落库"）。失败只 warn 不阻塞——因为后续还有租约扫描兜底。
        admissionService.markPersistedBestEffort(dto.getRequestId());

        // 按请求状态分派
        if (!Objects.equals(request.getProcessingMode(), ReservationProcessingModeEnum.INSTANT.getMode())) {
            return mapExisting(request);
        }
        if (Objects.equals(request.getStatus(), ReservationRequestStatusEnum.ACCEPTED.getStatus())) {
            return InstantReservationProcessor.accepted(request.getRequestId(), request.getOrderNo());
        }
        if (Objects.equals(request.getStatus(), ReservationRequestStatusEnum.FAILED.getStatus())) {
            if (admissionService.isHeld(dto, attempt.digest())) {
                admissionService.release(dto, attempt.digest(), false);
            }
            return mapExisting(request);
        }
        if (Objects.equals(request.getStatus(), ReservationRequestStatusEnum.PROCESSING.getStatus())
                || Objects.equals(request.getStatus(), ReservationRequestStatusEnum.MANUAL_REVIEW.getStatus())) {
            return mapExisting(request);
        }

        // 默认（PENDING 待处理）→ 抢占租约 + 同步处理
        String owner = "instant-http-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        boolean claimed = requestService.claim(
                request.getId(),
                owner,
                now,
                now.plusSeconds(leaseSeconds)
        );
        if (!claimed) {
            return mapExisting(requestService.findByRequestId(dto.getRequestId()));
        }
        return processor.process(request, owner);
    }

    private void releaseNewAdmissionAfterMysqlDuplicate(
            ResourceOrderCreateDto dto,
            InstantAdmissionService.AdmissionAttempt attempt,
            InstantAdmissionResultEnum admission,
            BizException original
    ) {
        if (admission != InstantAdmissionResultEnum.ADMITTED_NEW) {
            return;
        }
        try {
            admissionService.release(dto, attempt.digest(), false);
        } catch (RuntimeException releaseException) {
            original.addSuppressed(releaseException);
        }
    }

    private InstantReservationResultDto mapExisting(ReservationRequestEntity request) {
        if (request == null) {
            return InstantReservationProcessor.processing(null, "REQUEST_CONFIRMING", "请求状态确认中");
        }
        if (Objects.equals(request.getStatus(), ReservationRequestStatusEnum.ACCEPTED.getStatus())) {
            return InstantReservationProcessor.accepted(request.getRequestId(), request.getOrderNo());
        }
        if (Objects.equals(request.getStatus(), ReservationRequestStatusEnum.FAILED.getStatus())) {
            return InstantReservationProcessor.rejected(
                    request.getRequestId(),
                    "FAILED",
                    StringUtils.hasText(request.getLastError()) ? request.getLastError() : "预约失败"
            );
        }
        return InstantReservationProcessor.processing(
                request.getRequestId(),
                Objects.equals(request.getStatus(), ReservationRequestStatusEnum.MANUAL_REVIEW.getStatus())
                        ? "MANUAL_REVIEW" : "REQUEST_CONFIRMING",
                "请求处理中，请勿重复提交"
        );
    }

    private void validate(ResourceOrderCreateDto dto) {
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
}
