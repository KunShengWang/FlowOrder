CREATE DATABASE IF NOT EXISTS floworder
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE floworder;

DROP TABLE IF EXISTS fo_order_status_log;
DROP TABLE IF EXISTS fo_mq_message_log;
DROP TABLE IF EXISTS fo_stock_deduct_record;
DROP TABLE IF EXISTS fo_reservation_order;
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

CREATE TABLE fo_stock_deduct_record (
    id BIGINT NOT NULL PRIMARY KEY,
    deduct_no VARCHAR(64) NOT NULL COMMENT '库存预扣流水号',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '预约单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    resource_id BIGINT NOT NULL COMMENT '资源ID',
    stock_item_id BIGINT NOT NULL COMMENT '库存项ID',
    quantity INT NOT NULL COMMENT '预扣数量',
    request_id VARCHAR(128) NOT NULL COMMENT '请求幂等ID',
    status TINYINT NOT NULL COMMENT '状态：10已预扣 20已确认 30已释放 40失败 50人工确认',
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

UPDATE fo_stock_deduct_record
SET next_retry_time = COALESCE(next_retry_time, expire_time, NOW())
WHERE status = 10;

CREATE TABLE fo_mq_message_log (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键，如orderNo/deductNo',
    message_type VARCHAR(64) NOT NULL COMMENT '消息类型',
    topic VARCHAR(128) NOT NULL COMMENT 'MQ topic',
    content TEXT NOT NULL COMMENT '消息体JSON',
    status TINYINT NOT NULL COMMENT '状态：0初始化 10已发送 20已消费 30失败 40重试中',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最后一次错误',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_biz_key (biz_key),
    KEY idx_status_next_retry_time (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ消息日志表';

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