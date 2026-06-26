package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.entity.ReservationRequestEntity;

import java.time.LocalDateTime;
import java.util.List;
import com.javaup.dto.ReservationRequestResultDto;

public interface ReservationRequestService {

    String submit(ResourceOrderCreateDto dto, String traceId);

    List<ReservationRequestEntity> findClaimable(LocalDateTime now, int limit);

    boolean claim(Long id, String owner, LocalDateTime now, LocalDateTime claimUntil);

    void markSucceeded(Long id, String owner, String orderNo);

    void markOrderStateChanged(
            String requestId,
            String orderNo,
            Integer fromStatus,
            Integer toStatus,
            String eventType,
            LocalDateTime occurredAt
    );

    void markRetry(Long id, String owner, LocalDateTime nextRetryTime, String error);

    void markFailed(Long id, String owner, String error);

    void markManualReview(Long id, String owner, String error);

    void releaseClaim(Long id, String owner, LocalDateTime nextRetryTime, String error);

    int recoverExpired(LocalDateTime now, int limit, int maxRetry);

    ReservationRequestResultDto getResult(String requestId);
}
