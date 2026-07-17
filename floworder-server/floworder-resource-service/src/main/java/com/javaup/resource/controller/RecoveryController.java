package com.javaup.resource.controller;

import com.javaup.common.ApiResponse;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryExecuteResult;
import com.javaup.resource.dto.RecoveryPreviewResult;
import com.javaup.resource.dto.RecoveryCaseResult;
import com.javaup.resource.dto.ReservationRecoveryCheckResult;
import com.javaup.resource.service.RecoveryCaseService;
import com.javaup.resource.service.RecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/recovery")
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "floworder.admin",
        name = "enabled",
        havingValue = "true"
)
public class RecoveryController {

    private final RecoveryService recoveryService;

    private final RecoveryCaseService recoveryCaseService;

    @GetMapping("/cases/inspect")
    public ApiResponse<RecoveryCaseResult> inspectCase(
            @RequestParam("identifierType") String identifierType,
            @RequestParam("identifierValue") String identifierValue
    ) {
        return ApiResponse.success(recoveryCaseService.inspect(identifierType, identifierValue));
    }

    @PostMapping("/dead-letter/preview")
    public ApiResponse<RecoveryPreviewResult> previewDeadLetter(
            @RequestBody RecoveryDeadLetterRequest request
    ) {
        return ApiResponse.success(recoveryService.previewDeadLetter(request));
    }

    @PostMapping("/dead-letter/execute")
    public ApiResponse<RecoveryExecuteResult> executeDeadLetter(
            @RequestBody RecoveryDeadLetterRequest request
    ) {
        return ApiResponse.success(recoveryService.executeDeadLetter(request));
    }

    @GetMapping("/reservation/check")
    public ApiResponse<ReservationRecoveryCheckResult> checkReservation(
            @RequestParam("requestId") String requestId
    ) {
        return ApiResponse.success(recoveryService.checkReservation(requestId));
    }
}
