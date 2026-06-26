package com.javaup.resource.dto;

import lombok.Data;

@Data
public class RecoveryDeadLetterRequest {

    /**
     * 幂等请求号。preview 和 execute 使用同一个 actionRequestId，便于审计串联。
     */
    private String actionRequestId;

    private Long deadLetterId;

    /**
     * REPLAY / IGNORE。
     */
    private String actionType;

    private String operator;

    private String reason;

    /**
     * 仅 IGNORE 使用。true 表示人工确认后强制忽略。
     */
    private Boolean force;
}
