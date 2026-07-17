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

    /** 当前持有副作用执行权的实例/工具执行标识。 */
    private String executionOwner;

    /** EXECUTING 状态的可恢复租约，到期后只允许原 actionRequestId 重新抢占。 */
    private LocalDateTime executionLeaseUntil;

    private LocalDateTime lastHeartbeatAt;

    private Integer reconcileCount;

    private LocalDateTime reconciledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
