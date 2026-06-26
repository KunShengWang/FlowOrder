CREATE DATABASE IF NOT EXISTS floworder
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE floworder;

DROP TABLE IF EXISTS fo_order_status_log;
DROP TABLE IF EXISTS fo_recovery_action_log;
DROP TABLE IF EXISTS fo_mq_dead_letter;
DROP TABLE IF EXISTS fo_mq_consume_log;
DROP TABLE IF EXISTS fo_mq_outbox;
DROP TABLE IF EXISTS fo_stock_deduct_record;
DROP TABLE IF EXISTS fo_reservation_order;
DROP TABLE IF EXISTS fo_reservation_request;
DROP TABLE IF EXISTS fo_user_reservation_quota;
DROP TABLE IF EXISTS fo_stock_item;
DROP TABLE IF EXISTS fo_resource;

CREATE TABLE fo_resource (
    id BIGINT NOT NULL PRIMARY KEY,
    resource_code VARCHAR(64) NOT NULL COMMENT '资源编码',
    name VARCHAR(128) NOT NULL COMMENT '资源名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '资源描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    UNIQUE KEY uk_resource_code (resource_code),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源表';

CREATE TABLE fo_stock_item (
    id BIGINT NOT NULL PRIMARY KEY,
    stock_item_code VARCHAR(64) NOT NULL COMMENT '库存项编码',
    resource_id BIGINT NOT NULL COMMENT '资源ID',
    name VARCHAR(128) NOT NULL COMMENT '库存项名称',
    total_stock INT NOT NULL DEFAULT 0 COMMENT '总库存',
    available_stock INT NOT NULL DEFAULT 0 COMMENT '可用库存，MySQL兜底/展示用',
    locked_stock INT NOT NULL DEFAULT 0 COMMENT '已锁定库存',
    sold_stock INT NOT NULL DEFAULT 0 COMMENT '已确认库存',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    start_time DATETIME DEFAULT NULL COMMENT '预约开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '预约结束时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_stock_item_code (stock_item_code),
    KEY idx_resource_id (resource_id),
    KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存项表';

CREATE TABLE fo_user_reservation_quota (
   id BIGINT NOT NULL PRIMARY KEY,
   resource_id BIGINT NOT NULL COMMENT '资源ID',
   stock_item_id BIGINT NOT NULL COMMENT '库存项ID',
   user_id BIGINT NOT NULL COMMENT '用户ID',
   status TINYINT NOT NULL DEFAULT 1 COMMENT '资格状态：1有效 0无效',
   limit_quantity INT NOT NULL COMMENT '当前库存项累计限购数量',
   used_quantity INT NOT NULL DEFAULT 0 COMMENT '已预扣或已成交数量',
   valid_from DATETIME DEFAULT NULL COMMENT '资格生效时间，为空表示不限制',
   valid_until DATETIME DEFAULT NULL COMMENT '资格失效时间，为空表示不限制',
   version INT NOT NULL DEFAULT 0 COMMENT '版本号',
   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   UNIQUE KEY uk_stock_item_user (stock_item_id, user_id),
   KEY idx_resource_user_status (resource_id, user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户预约资格与额度表';

CREATE TABLE fo_reservation_request (
    id BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    stock_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 10处理中 20成功 30待重试 40失败 50人工审核',
    order_status TINYINT DEFAULT NULL COMMENT '订单履约状态：10已预约 20已确认 30已取消 40已超时',
    latest_order_event_type VARCHAR(64) DEFAULT NULL COMMENT '最后一次已处理订单状态事件',
    latest_order_event_time DATETIME DEFAULT NULL COMMENT '最后一次已处理订单状态事件发生时间',
    order_event_version INT NOT NULL DEFAULT 0 COMMENT '订单状态事件本地递增版本',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME DEFAULT NULL,
    claim_owner VARCHAR(64) DEFAULT NULL,
    claim_until DATETIME DEFAULT NULL,
    last_error VARCHAR(1024) DEFAULT NULL,
    started_at DATETIME DEFAULT NULL,
    finished_at DATETIME DEFAULT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_status_retry (status, next_retry_time),
    KEY idx_status_claim (status, claim_until),
    KEY idx_order_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V8持久化预约请求表';

CREATE TABLE fo_reservation_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL COMMENT '预约单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    resource_id BIGINT NOT NULL COMMENT '资源ID',
    stock_item_id BIGINT NOT NULL COMMENT '库存项ID',
    quantity INT NOT NULL COMMENT '预约数量',
    status TINYINT NOT NULL COMMENT '订单状态：0初始化 10已预约 20已确认 30已取消 40已超时 50失败',
    request_id VARCHAR(128) NOT NULL COMMENT '请求幂等ID',
    deduct_no VARCHAR(64) DEFAULT NULL COMMENT '库存预扣流水号',
    expire_time DATETIME DEFAULT NULL COMMENT '超时时间',
    confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
    canceled_at DATETIME DEFAULT NULL COMMENT '取消时间',
    cancel_reason VARCHAR(255) DEFAULT NULL COMMENT '取消原因',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_user_id (user_id),
    KEY idx_stock_item_id (stock_item_id),
    KEY idx_status_expire_time (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约订单表';

ALTER TABLE fo_reservation_order
    ADD UNIQUE KEY uk_deduct_no (deduct_no);

CREATE TABLE fo_stock_deduct_record (
    id BIGINT NOT NULL PRIMARY KEY,
    deduct_no VARCHAR(64) NOT NULL COMMENT '库存预扣流水号',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '预约单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    resource_id BIGINT NOT NULL COMMENT '资源ID',
    stock_item_id BIGINT NOT NULL COMMENT '库存项ID',
    quantity INT NOT NULL COMMENT '预扣数量',
    request_id VARCHAR(128) NOT NULL COMMENT '请求幂等ID',
    status TINYINT NOT NULL COMMENT '状态：10已预扣 20订单已创建 30已释放 40失败 50人工确认 60已成交',
    expire_time DATETIME DEFAULT NULL COMMENT '预扣过期时间',
    release_reason VARCHAR(255) DEFAULT NULL COMMENT '释放原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deduct_no (deduct_no),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_order_no (order_no),
    KEY idx_stock_item_id (stock_item_id),
    KEY idx_status_expire_time (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存预扣记录表';

ALTER TABLE fo_stock_deduct_record
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '订单不存在确认次数',
    ADD COLUMN next_retry_time DATETIME DEFAULT NULL COMMENT '下次确认时间',
    ADD COLUMN last_error VARCHAR(1024) DEFAULT NULL COMMENT '最后确认结果',
    ADD INDEX idx_status_next_retry_time (status, next_retry_time);

ALTER TABLE fo_stock_deduct_record
    ADD COLUMN query_error_count INT NOT NULL DEFAULT 0
        COMMENT '订单查询异常次数';

ALTER TABLE fo_stock_deduct_record
    ADD COLUMN create_mode TINYINT NOT NULL DEFAULT 2 COMMENT '创建模式：2同步 3异步';

ALTER TABLE fo_stock_deduct_record
    DROP INDEX idx_status_next_retry_time,
    ADD INDEX idx_mode_status_next_retry_time
        (create_mode, status, next_retry_time);


UPDATE fo_stock_deduct_record
SET next_retry_time = COALESCE(next_retry_time, expire_time, NOW())
WHERE status = 10;

CREATE TABLE fo_mq_outbox (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL COMMENT '消息唯一ID',
    producer_service VARCHAR(64) NOT NULL COMMENT '生产者服务',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键，如deductNo/orderNo',
    message_type VARCHAR(64) NOT NULL COMMENT '消息类型',
    exchange_name VARCHAR(128) NOT NULL COMMENT 'RabbitMQ交换机',
    routing_key VARCHAR(128) NOT NULL COMMENT 'RabbitMQ路由键',
    content TEXT NOT NULL COMMENT '消息JSON内容',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待发送 10发送中 20已确认 30待重试 40死亡',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '发送重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次发送时间',
    claim_until DATETIME DEFAULT NULL COMMENT '发送任务抢占租约截止时间',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最后发送错误',
    sent_at DATETIME DEFAULT NULL COMMENT 'Broker确认时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    UNIQUE KEY uk_producer_biz_type
        (producer_service, biz_key, message_type),
    KEY idx_producer_status_retry
        (producer_service, status, next_retry_time),
    KEY idx_status_claim
        (status, claim_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    COMMENT='MQ事务Outbox表';

CREATE TABLE fo_recovery_action_log (
   id BIGINT NOT NULL PRIMARY KEY,
   action_request_id VARCHAR(128) NOT NULL COMMENT '恢复动作幂等号',
   action_type VARCHAR(64) NOT NULL COMMENT '动作类型：REPLAY/IGNORE/CHECK等',
   target_type VARCHAR(64) NOT NULL COMMENT '目标类型：DEAD_LETTER/RESERVATION等',
   target_key VARCHAR(128) NOT NULL COMMENT '目标主键或业务键',
   status TINYINT NOT NULL DEFAULT 0 COMMENT '0已预览 10执行中 20成功 30失败',
   operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
   reason VARCHAR(512) DEFAULT NULL COMMENT '操作原因',
   preview_result TEXT DEFAULT NULL COMMENT '预览结果快照',
   execute_result TEXT DEFAULT NULL COMMENT '执行结果快照',
   last_error VARCHAR(1024) DEFAULT NULL COMMENT '最后错误',
   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   UNIQUE KEY uk_action_request_id (action_request_id),
   KEY idx_target (target_type, target_key),
   KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='恢复动作审计日志';

CREATE TABLE fo_mq_consume_log (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL COMMENT '消息唯一ID',
    consumer_group VARCHAR(64) NOT NULL COMMENT '消费者组',
    message_type VARCHAR(64) NOT NULL COMMENT '消息类型',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0处理中 10消费成功',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_consumer (message_id, consumer_group),
    KEY idx_biz_key (biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ消费幂等记录表';

CREATE TABLE fo_order_status_log (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL COMMENT '预约单号',
    from_status TINYINT DEFAULT NULL COMMENT '原状态',
    to_status TINYINT NOT NULL COMMENT '目标状态',
    event VARCHAR(64) NOT NULL COMMENT '事件：CREATE/CONFIRM/CANCEL/TIMEOUT/FAIL',
    operator_type VARCHAR(32) NOT NULL COMMENT '操作方：SYSTEM/USER/MQ',
    remark VARCHAR(512) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_no (order_no),
    KEY idx_event (event)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流转日志表';

CREATE TABLE fo_mq_dead_letter (
   id BIGINT NOT NULL PRIMARY KEY,
   message_id VARCHAR(64) NOT NULL,
   dead_queue VARCHAR(128) NOT NULL,
   producer_service VARCHAR(64) NOT NULL,
   message_type VARCHAR(64) DEFAULT NULL,
   biz_key VARCHAR(128) DEFAULT NULL,
   exchange_name VARCHAR(128) NOT NULL,
   routing_key VARCHAR(128) NOT NULL,
   content TEXT NOT NULL,
   death_reason VARCHAR(255) DEFAULT NULL,
   status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 10重放中 20已解决 30已忽略',
   replay_count INT NOT NULL DEFAULT 0,
   last_error VARCHAR(1024) DEFAULT NULL,
   replayed_at DATETIME DEFAULT NULL,
   resolved_at DATETIME DEFAULT NULL,
   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   handled_by VARCHAR(64) DEFAULT NULL COMMENT '处理人',
   resolution_note VARCHAR(512) DEFAULT NULL COMMENT '处理说明',
   UNIQUE KEY uk_queue_message (dead_queue, message_id),
   KEY idx_status_replayed (status, replayed_at),
   KEY idx_status_created (status, created_at),
   KEY idx_biz_key (biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ消费死信处理记录';

