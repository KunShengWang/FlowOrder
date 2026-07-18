DROP PROCEDURE IF EXISTS inject_incident_mq_timeout;
DELIMITER $$
CREATE PROCEDURE inject_incident_mq_timeout()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE req VARCHAR(128);
    DECLARE ord VARCHAR(64);
    DECLARE deduct VARCHAR(64);

    INSERT INTO fo_stock_item (
        id, stock_item_code, resource_id, name,
        total_stock, available_stock, locked_stock, sold_stock,
        status, version, created_at, updated_at, deleted
    ) VALUES (
        9200000000000001, 'IC-MQTIMEOUT-STOCK', 1, 'Incident Command MQ timeout fixture',
        100, 97, 3, 0, 1, 0, NOW(), NOW(), 0
    );

    WHILE i <= 3 DO
        SET req = CONCAT('IC-MQTIMEOUT-REQ-', LPAD(i, 3, '0'));
        SET ord = CONCAT('IC-MQTIMEOUT-ORD-', LPAD(i, 3, '0'));
        SET deduct = CONCAT('IC-MQTIMEOUT-DED-', LPAD(i, 3, '0'));
        INSERT INTO fo_reservation_request (
            id, request_id, trace_id, user_id, resource_id, stock_item_id,
            quantity, order_no, status, order_status, order_event_version,
            retry_count, version, created_at, updated_at
        ) VALUES (
            9200000000010000 + i, req, CONCAT('IC-MQTIMEOUT-TRACE-', i), 9200000000000100 + i,
            1, 9200000000000001, 1, ord, 20, 40, 1, 0, 0, NOW(), NOW()
        );
        INSERT INTO fo_reservation_order (
            id, order_no, user_id, resource_id, stock_item_id, quantity,
            status, request_id, deduct_no, expire_time, cancel_reason,
            version, created_at, updated_at, deleted
        ) VALUES (
            9200000000020000 + i, ord, 9200000000000100 + i, 1, 9200000000000001, 1,
            40, req, deduct, DATE_SUB(NOW(), INTERVAL 5 MINUTE),
            'Incident Command MQ timeout fixture', 1, NOW(), NOW(), 0
        );
        INSERT INTO fo_stock_deduct_record (
            id, deduct_no, order_no, user_id, resource_id, stock_item_id,
            quantity, request_id, status, expire_time,
            retry_count, query_error_count, create_mode, created_at, updated_at
        ) VALUES (
            9200000000030000 + i, deduct, ord, 9200000000000100 + i, 1, 9200000000000001,
            1, req, 20, DATE_ADD(NOW(), INTERVAL 10 MINUTE), 0, 0, 3, NOW(), NOW()
        );
        INSERT INTO fo_mq_dead_letter (
            id, message_id, dead_queue, producer_service, message_type, biz_key,
            exchange_name, routing_key, content, death_reason,
            status, replay_count, created_at, updated_at
        ) VALUES (
            9200000000040000 + i, CONCAT('IC-MQTIMEOUT-MSG-', LPAD(i, 3, '0')),
            'floworder.incident.e2e.dlq', 'floworder-order-service', 'ORDER_TIMEOUT', deduct,
            'floworder.order.state.exchange', 'order.state.changed',
            JSON_OBJECT('requestId', req, 'orderNo', ord, 'deductNo', deduct),
            'Persistent fact must survive RabbitMQ Management timeout', 0, 0, NOW(), NOW()
        );
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;
CALL inject_incident_mq_timeout();
DROP PROCEDURE inject_incident_mq_timeout;

SELECT 3 AS persistedDeadLetterRecordCount,
       'Start enterprise-agent with RABBITMQ_MANAGEMENT_BASE_URL=http://127.0.0.1:1' AS deterministicFaultInjection,
       'MQ_TIMEOUT_PARTIAL' AS fixture;
