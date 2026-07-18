SELECT 'HAPPY_CONSISTENT' AS fixture,
       (SELECT COUNT(*) FROM fo_reservation_order WHERE request_id LIKE 'IC-HAPPY-REQ-%' AND status IN (30, 40)) AS terminal_order_count,
       (SELECT COUNT(*) FROM fo_stock_deduct_record WHERE request_id LIKE 'IC-HAPPY-REQ-%' AND status NOT IN (30, 60)) AS unreleased_deduct_count,
       (SELECT COUNT(*) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-HAPPY-%') AS dead_letter_record_count,
       (SELECT COUNT(DISTINCT biz_key) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-HAPPY-%') AS distinct_biz_key_count;

SELECT 'CONFLICT_126_100_93' AS fixture,
       (SELECT COUNT(*) FROM fo_reservation_order WHERE request_id LIKE 'IC-CONFLICT-REQ-%' AND status IN (30, 40)) AS terminal_order_count,
       (SELECT COUNT(*) FROM fo_stock_deduct_record WHERE request_id LIKE 'IC-CONFLICT-REQ-%' AND status NOT IN (30, 60)) AS unreleased_deduct_count,
       (SELECT COUNT(*) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-CONFLICT-%') AS dead_letter_record_count,
       (SELECT COUNT(DISTINCT biz_key) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-CONFLICT-%') AS distinct_biz_key_count,
       ((SELECT COUNT(*) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-CONFLICT-%')
        - (SELECT COUNT(DISTINCT biz_key) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-CONFLICT-%')) AS duplicate_record_count;

SELECT 'MQ_TIMEOUT_PARTIAL' AS fixture,
       (SELECT COUNT(*) FROM fo_mq_dead_letter WHERE message_id LIKE 'IC-MQTIMEOUT-%') AS persisted_dead_letter_record_count;
