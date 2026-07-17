SELECT
    id,
    request_id,
    order_status,
    latest_order_event_type,
    updated_at
FROM fo_reservation_request
WHERE request_id = 'ORDERCARE-M05-REQUEST';

SELECT
    id,
    order_no,
    request_id,
    status AS order_status,
    deduct_no,
    updated_at
FROM fo_reservation_order
WHERE request_id = 'ORDERCARE-M05-REQUEST';

SELECT
    deduct_no,
    status AS deduct_status,
    release_reason,
    last_error,
    updated_at
FROM fo_stock_deduct_record
WHERE deduct_no = 'ORDERCARE-M05-DEDUCT';

SELECT
    stock_item_code,
    total_stock,
    available_stock,
    locked_stock,
    sold_stock,
    total_stock = available_stock + locked_stock + sold_stock AS invariant_ok
FROM fo_stock_item
WHERE stock_item_code = 'ORDERCARE-M05-STOCK';

SELECT
    message_id,
    status AS dead_letter_status,
    replay_count,
    handled_by,
    resolution_note,
    last_error
FROM fo_mq_dead_letter
WHERE id = 9000000000000505;

SELECT
    message_id,
    consumer_group,
    status AS consume_status,
    biz_key
FROM fo_mq_consume_log
WHERE message_id = 'ORDERCARE-M05-STATE-MESSAGE';
