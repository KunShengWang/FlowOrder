package com.javaup.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IncidentSourceReference {
    private String sourceSystem;
    private String sourceType;
    private String sourceId;
    private LocalDateTime observedAt;
}
