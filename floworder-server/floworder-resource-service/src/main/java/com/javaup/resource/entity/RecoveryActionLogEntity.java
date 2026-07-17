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
     * 0 PREVIEWED, 10 EXECUTING, 20 SUBMITTED, 30 FAILED。
     * SUBMITTED 只表示命令已可靠提交，业务结果需要独立回查。
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
