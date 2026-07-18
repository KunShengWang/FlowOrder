package com.javaup.dto;

import lombok.Data;

import java.util.List;

/**
 * Bounded internal query used by the incident fact service.
 */
@Data
public class OrderFactBatchRequest {

    private List<String> requestIds;
}
