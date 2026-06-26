package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fo_recovery_action_log")
public class RecoveryActionLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String actionRequestId;

    private String actionType;

    private String targetType;

    private String targetKey;

    /**
     * 0 PREVIEWED, 10 EXECUTING, 20 SUCCEEDED, 30 FAILED。
     */
    private Integer status;

    private String operator;

    private String reason;

    private String previewResult;

    private String executeResult;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
