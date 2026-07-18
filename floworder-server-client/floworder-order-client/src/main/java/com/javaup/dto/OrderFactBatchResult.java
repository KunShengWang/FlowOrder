package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderFactBatchResult {

    private LocalDateTime observedAt;
    private List<OrderFactItemDto> items;
    private List<String> missingRequestIds;
}
