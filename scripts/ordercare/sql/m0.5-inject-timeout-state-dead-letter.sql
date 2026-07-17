SET @stock_item_id = 9000000000000501;
SET @quota_id = 9000000000000502;
SET @request_row_id = 9000000000000503;
SET @deduct_row_id = 9000000000000504;
SET @dead_letter_id = 9000000000000505;
SET @outbox_id = 9000000000000506;
SET @order_row_id = 9000000000000508;
SET @request_id = 'ORDERCARE-M05-REQUEST';
SET @trace_id = 'ORDERCARE-M05-TRACE';
SET @order_no = 'ORDERCARE-M05-ORDER';
SET @deduct_no = 'ORDERCARE-M05-DEDUCT';
SET @message_id = 'ORDERCARE-M05-STATE-MESSAGE';
SET @content = JSON_OBJECT(
    'messageId', @message_id,
    'traceId', @trace_id,
    'eventType', 'ORDER_TIMEOUT',
    'requestId', @request_id,
    'orderNo', @order_no,
    'deductNo', @deduct_no,
    'stockItemId', @stock_item_id,
    'quantity', 3,
    'fromStatus', 10,
    'toStatus', 40,
    'occurredAt', DATE_FORMAT(NOW(), '%Y-%m-%dT%H:%i:%s')
);

INSERT INTO fo_stock_item (
    id, stock_item_code, resource_id, name,
    total_stock, available_stock, locked_stock, sold_stock,
    status, version, created_at, updated_at, deleted
) VALUES (
    @stock_item_id, 'ORDERCARE-M05-STOCK', 1, 'OrderCare M0.5 demo stock',
    10, 7, 3, 0,
    1, 0, NOW(), NOW(), 0
);

INSERT INTO fo_user_reservation_quota (
    id, resource_id, stock_item_id, user_id, status,
    limit_quantity, used_quantity, valid_from, valid_until,
    version, created_at, updated_at
) VALUES (
    @quota_id, 1, @stock_item_id, 9000000000000507, 1,
    5, 3, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY),
    0, NOW(), NOW()
);

INSERT INTO fo_reservation_request (
    id, request_id, trace_id, user_id, resource_id, stock_item_id,
    quantity, order_no, status, order_status, order_event_version,
    retry_count, version, created_at, updated_at
) VALUES (
    @request_row_id, @request_id, @trace_id, 9000000000000507, 1, @stock_item_id,
    3, @order_no, 20, 10, 0,
    0, 0, NOW(), NOW()
);

-- 订单服务已完成超时终态；资源服务因为状态消息进入死信而仍停留在 RESERVED。
INSERT INTO fo_reservation_order (
    id, order_no, user_id, resource_id, stock_item_id,
    quantity, status, request_id, deduct_no, expire_time,
    cancel_reason, version, created_at, updated_at, deleted
) VALUES (
    @order_row_id, @order_no, 9000000000000507, 1, @stock_item_id,
    3, 40, @request_id, @deduct_no, DATE_SUB(NOW(), INTERVAL 5 MINUTE),
    'OrderCare fixture timeout', 1, DATE_SUB(NOW(), INTERVAL 15 MINUTE), NOW(), 0
);

INSERT INTO fo_stock_deduct_record (
    id, deduct_no, order_no, user_id, resource_id, stock_item_id,
    quantity, request_id, status, expire_time,
    retry_count, query_error_count, create_mode, created_at, updated_at
) VALUES (
    @deduct_row_id, @deduct_no, @order_no, 9000000000000507, 1, @stock_item_id,
    3, @request_id, 20, DATE_ADD(NOW(), INTERVAL 10 MINUTE),
    0, 0, 3, NOW(), NOW()
);

INSERT INTO fo_mq_outbox (
    id, message_id, producer_service, biz_key, message_type,
    exchange_name, routing_key, content, status, retry_count,
    sent_at, created_at, updated_at
) VALUES (
    @outbox_id, @message_id, 'floworder-order-service', @deduct_no, 'ORDER_TIMEOUT',
    'floworder.order.state.exchange', 'order.state.changed', @content, 20, 0,
    NOW(), NOW(), NOW()
);

INSERT INTO fo_mq_dead_letter (
    id, message_id, dead_queue, producer_service, message_type, biz_key,
    exchange_name, routing_key, content, death_reason,
    status, replay_count, created_at, updated_at
) VALUES (
    @dead_letter_id, @message_id, 'floworder.order.state.dlq',
    'floworder-order-service', 'ORDER_TIMEOUT', @deduct_no,
    'floworder.order.state.exchange', 'order.state.changed', @content,
    'OrderCare M0.5 injected consumer failure',
    0, 0, NOW(), NOW()
);

SELECT
    @dead_letter_id AS dead_letter_id,
    @request_id AS request_id,
    @deduct_no AS deduct_no,
    'PENDING timeout-state dead letter fixture created' AS fixture_status;
