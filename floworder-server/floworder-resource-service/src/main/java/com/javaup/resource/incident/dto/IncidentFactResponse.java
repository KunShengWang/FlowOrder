package com.javaup.resource.incident.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class IncidentFactResponse<T> {

    private String schemaVersion;
    private String sourceSystem;
    private String sourceReference;
    private String scopeHash;
    private OffsetDateTime observedAt;
    private Boolean truncated;
    private List<String> missingRequestIds;
    private T facts;
}
