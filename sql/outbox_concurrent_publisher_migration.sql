-- Outbox 有界并发发布租约身份。
-- claim_token 是单次租约的 fencing token；claim_owner 只用于定位实例和观测。
ALTER TABLE fo_mq_outbox
    ADD COLUMN claim_owner VARCHAR(128) DEFAULT NULL COMMENT '发布租约实例标识' AFTER next_retry_time,
    ADD COLUMN claim_token VARCHAR(64) DEFAULT NULL COMMENT '单次发布租约fencing token' AFTER claim_owner,
    MODIFY COLUMN next_retry_time DATETIME(3) DEFAULT NULL COMMENT '下次发送时间',
    MODIFY COLUMN claim_until DATETIME(3) DEFAULT NULL COMMENT '发送任务抢占租约截止时间',
    DROP INDEX idx_status_claim,
    ADD INDEX idx_producer_status_claim (producer_service, status, claim_until);

-- 迁移需在发布任务停止时执行；旧版本没有 token，不能让旧 SENDING 永久卡住。
UPDATE fo_mq_outbox
SET status = 30,
    next_retry_time = NOW(3),
    claim_owner = NULL,
    claim_token = NULL,
    claim_until = NULL,
    last_error = 'Outbox并发发布迁移：释放旧SENDING租约'
WHERE status = 10;
