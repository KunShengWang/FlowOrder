ALTER TABLE fo_reservation_request
    ADD COLUMN processing_mode TINYINT NOT NULL DEFAULT 0
        COMMENT '处理模式：0 V8持久化异步 1 Instant即时抢票'
        AFTER quantity;

ALTER TABLE fo_reservation_request
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '0待处理 10处理中 20已受理 30待重试 40失败 50人工审核',
    MODIFY COLUMN order_status TINYINT DEFAULT NULL
        COMMENT '订单履约状态：10已预约 20已确认 30已取消 40已超时 50创建失败';
