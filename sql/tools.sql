DELETE FROM fo_recovery_action_log;
DELETE FROM fo_mq_dead_letter;
DELETE FROM fo_mq_consume_log;
DELETE FROM fo_mq_outbox;
DELETE FROM fo_stock_deduct_record;
DELETE FROM fo_reservation_order;
DELETE FROM fo_order_status_log;
DELETE FROM fo_reservation_request;

UPDATE fo_stock_item
SET total_stock = 1000,
    available_stock = 1000,
    locked_stock = 0,
    sold_stock = 0,
    version = 0,
    status = 1,
    start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR),
    end_time = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE id = 1;

UPDATE fo_user_reservation_quota
SET limit_quantity = 1000,
    used_quantity = 0,
    version = 0,
    valid_from = DATE_SUB(CURDATE(), INTERVAL 1 DAY),
    valid_until = DATE_ADD(CURDATE(), INTERVAL 1 DAY)
WHERE user_id = 1001
  AND stock_item_id = 1;
