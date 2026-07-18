DROP PROCEDURE IF EXISTS inject_incident_conflict;
DELIMITER $$
CREATE PROCEDURE inject_incident_conflict()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE mappedIndex INT;
    DECLARE req VARCHAR(128);
    DECLARE ord VARCHAR(64);
    DECLARE deduct VARCHAR(64);

    INSERT INTO fo_stock_item (
        id, stock_item_code, resource_id, name,
        total_stock, available_stock, locked_stock, sold_stock,
        status, version, created_at, updated_at, deleted
    ) VALUES (
        9300000000000001, 'IC-CONFLICT-STOCK', 1, 'Incident Command 126/100/93 fixture',
        1000, 907, 93, 0, 1, 0, NOW(), NOW(), 0
    );

    WHILE i <= 100 DO
        SET req = CONCAT('IC-CONFLICT-REQ-', LPAD(i, 3, '0'));
        SET ord = CONCAT('IC-CONFLICT-ORD-', LPAD(i, 3, '0'));
        SET deduct = CONCAT('IC-CONFLICT-DED-', LPAD(i, 3, '0'));

        INSERT INTO fo_reservation_request (
            id, request_id, trace_id, user_id, resource_id, stock_item_id,
            quantity, order_no, status, order_status, order_event_version,
            retry_count, version, created_at, updated_at
        ) VALUES (
            9300000000010000 + i, req, CONCAT('IC-CONFLICT-TRACE-', i), 9300000000001000 + i,
            1, 9300000000000001, 1, ord, 20, 40, 1, 0, 0, NOW(), NOW()
        );

        INSERT INTO fo_reservation_order (
            id, order_no, user_id, resource_id, stock_item_id, quantity,
            status, request_id, deduct_no, expire_time, cancel_reason,
            version, created_at, updated_at, deleted
        ) VALUES (
            9300000000020000 + i, ord, 9300000000001000 + i, 1, 9300000000000001, 1,
            40, req, deduct, DATE_SUB(NOW(), INTERVAL 5 MINUTE),
            'Incident Command 126/100/93 fixture', 1, NOW(), NOW(), 0
        );

        INSERT INTO fo_stock_deduct_record (
            id, deduct_no, order_no, user_id, resource_id, stock_item_id,
            quantity, request_id, status, expire_time,
            retry_count, query_error_count, create_mode, created_at, updated_at
        ) VALUES (
            9300000000030000 + i, deduct, ord, 9300000000001000 + i, 1, 9300000000000001,
            1, req, IF(i <= 93, 20, 30), DATE_ADD(NOW(), INTERVAL 10 MINUTE),
            0, 0, 3, NOW(), NOW()
        );

        INSERT INTO fo_mq_dead_letter (
            id, message_id, dead_queue, producer_service, message_type, biz_key,
            exchange_name, routing_key, content, death_reason,
            status, replay_count, created_at, updated_at
        ) VALUES (
            9300000000040000 + i, CONCAT('IC-CONFLICT-MSG-', LPAD(i, 3, '0')),
            'floworder.incident.e2e.dlq', 'floworder-order-service', 'ORDER_TIMEOUT', deduct,
            'floworder.order.state.exchange', 'order.state.changed',
            JSON_OBJECT('requestId', req, 'orderNo', ord, 'deductNo', deduct),
            'Incident Command 126/100/93 base record', 0, 0, NOW(), NOW()
        );
        SET i = i + 1;
    END WHILE;

    SET i = 101;
    WHILE i <= 126 DO
        SET mappedIndex = i - 100;
        SET req = CONCAT('IC-CONFLICT-REQ-', LPAD(mappedIndex, 3, '0'));
        SET ord = CONCAT('IC-CONFLICT-ORD-', LPAD(mappedIndex, 3, '0'));
        SET deduct = CONCAT('IC-CONFLICT-DED-', LPAD(mappedIndex, 3, '0'));
        INSERT INTO fo_mq_dead_letter (
            id, message_id, dead_queue, producer_service, message_type, biz_key,
            exchange_name, routing_key, content, death_reason,
            status, replay_count, created_at, updated_at
        ) VALUES (
            9300000000040000 + i, CONCAT('IC-CONFLICT-MSG-', LPAD(i, 3, '0')),
            'floworder.incident.e2e.dlq', 'floworder-order-service', 'ORDER_TIMEOUT', deduct,
            'floworder.order.state.exchange', 'order.state.changed',
            JSON_OBJECT('requestId', req, 'orderNo', ord, 'deductNo', deduct, 'duplicateOrdinal', i),
            'Incident Command deterministic duplicate dead letter', 0, 0, NOW(), NOW()
        );
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;
CALL inject_incident_conflict();
DROP PROCEDURE inject_incident_conflict;

SELECT 100 AS terminalOrderCount, 93 AS unreleasedDeductCount,
       126 AS deadLetterRecordCount, 100 AS distinctDeadLetterRequestIdCount,
       26 AS duplicateRecordCount, 'CONFLICT_126_100_93' AS fixture;
