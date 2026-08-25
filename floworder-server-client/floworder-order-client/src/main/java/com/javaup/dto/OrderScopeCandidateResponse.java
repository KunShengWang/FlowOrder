package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderScopeCandidateResponse {
    private String discoveryRequestId;
    private LocalDateTime observedAt;
    private List<OrderScopeCandidateItem> candidates;
    private Integer candidateCount;
    private Boolean truncated;
    private String nextCursor;
}
