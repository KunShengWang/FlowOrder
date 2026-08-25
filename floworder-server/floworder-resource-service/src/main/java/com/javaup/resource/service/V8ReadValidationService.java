package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class V8ReadValidationService {

    private final ReservationAdmissionService
            admissionService;

    private final ThreadPoolTaskExecutor executor;

    public V8ReadValidationService(
            ReservationAdmissionService admissionService,
            @Qualifier("v8ValidationExecutor")
            ThreadPoolTaskExecutor executor
    ) {
        this.admissionService = admissionService;
        this.executor = executor;
    }

    public void validate(ResourceOrderCreateDto dto) {
        LocalDateTime now = LocalDateTime.now();

        // 校验库存项本身是否可预约
        CompletableFuture<Void> stockFuture =
                CompletableFuture.runAsync(
                        () -> admissionService
                                .checkStockItem(dto, now),
                        executor
                );

        // 校验用户是否具备资格且额度足够
        CompletableFuture<Void> quotaFuture =
                CompletableFuture.runAsync(
                        () -> admissionService
                                .checkQuota(dto, now),
                        executor
                );

        try {
            CompletableFuture.allOf(
                    stockFuture,
                    quotaFuture
            ).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            throw new IllegalStateException(
                    "V8只读校验执行失败",
                    cause
            );
        }
    }
}