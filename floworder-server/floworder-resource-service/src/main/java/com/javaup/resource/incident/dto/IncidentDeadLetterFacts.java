package com.javaup.resource.incident.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IncidentDeadLetterFacts {

    private Integer recordCount;
    private Integer totalMatchingRecordCount;
    private Integer distinctBizKeyCount;
    private Integer distinctRequestIdCount;
    private Integer duplicateRecordCount;
    private Integer unmappedRecordCount;
    private List<String> bizKeys;
    private List<String> requestIds;
    private List<Long> deadLetterIds;
    private List<DuplicateGroup> duplicateGroups;
    private List<DeadLetterFact> items;

    @Data
    public static class DuplicateGroup {
        private String bizKey;
        private Integer recordCount;
        private List<Long> deadLetterIds;
    }

    @Data
    public static class DeadLetterFact {
        private Long deadLetterId;
        private String messageId;
        private String deadQueue;
        private String messageType;
        private String bizKey;
        private String requestId;
        private Integer status;
        private Integer replayCount;
        private String deathReason;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
