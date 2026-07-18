package com.javaup.resource.incident.dto;

import lombok.Data;

import java.util.List;

@Data
public class IncidentFactQueryRequest {

    private String incidentId;
    private String snapshotId;
    private String scopeHash;
    private List<String> requestIds;
    private List<String> queueNames;
    private Integer maxRecords;
}
