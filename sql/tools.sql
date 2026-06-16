DELETE FROM fo_mq_consume_log;
DELETE FROM fo_mq_outbox;
DELETE FROM fo_stock_deduct_record;
DELETE FROM fo_reservation_order;
DELETE FROM fo_order_status_log;

UPDATE fo_stock_item
SET available_stock = 10,
    locked_stock = 0,
    sold_stock = 0,
    version = 0,
    status = 1,
    start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR),
    end_time = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE id = 1;