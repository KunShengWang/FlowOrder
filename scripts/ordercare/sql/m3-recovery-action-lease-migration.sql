USE floworder;

DROP PROCEDURE IF EXISTS migrate_ordercare_m3_action_lease;

DELIMITER $$
CREATE PROCEDURE migrate_ordercare_m3_action_lease()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND COLUMN_NAME = 'execution_owner'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD COLUMN execution_owner VARCHAR(128) DEFAULT NULL COMMENT '当前执行租约持有者' AFTER last_error;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND COLUMN_NAME = 'execution_lease_until'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD COLUMN execution_lease_until DATETIME DEFAULT NULL COMMENT '执行租约到期时间' AFTER execution_owner;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND COLUMN_NAME = 'last_heartbeat_at'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD COLUMN last_heartbeat_at DATETIME DEFAULT NULL COMMENT '执行租约最近心跳' AFTER execution_lease_until;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND COLUMN_NAME = 'reconcile_count'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD COLUMN reconcile_count INT NOT NULL DEFAULT 0 COMMENT '对账/过期租约接管次数' AFTER last_heartbeat_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND COLUMN_NAME = 'reconciled_at'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD COLUMN reconciled_at DATETIME DEFAULT NULL COMMENT '最近一次确定性对账完成时间' AFTER reconcile_count;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'fo_recovery_action_log'
          AND INDEX_NAME = 'idx_execution_lease'
    ) THEN
        ALTER TABLE fo_recovery_action_log
            ADD KEY idx_execution_lease (status, execution_lease_until);
    END IF;
END$$
DELIMITER ;

CALL migrate_ordercare_m3_action_lease();
DROP PROCEDURE migrate_ordercare_m3_action_lease;
