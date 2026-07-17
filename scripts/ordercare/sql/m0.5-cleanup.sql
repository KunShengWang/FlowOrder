DELETE FROM fo_mq_consume_log
WHERE message_id = 'ORDERCARE-M05-STATE-MESSAGE'
   OR biz_key = 'ORDERCARE-M05-DEDUCT';

DELETE FROM fo_mq_dead_letter
WHERE id = 9000000000000505
   OR message_id = 'ORDERCARE-M05-STATE-MESSAGE';

DELETE FROM fo_mq_outbox
WHERE id = 9000000000000506
   OR message_id = 'ORDERCARE-M05-STATE-MESSAGE';

DELETE FROM fo_stock_deduct_record
WHERE deduct_no = 'ORDERCARE-M05-DEDUCT';

DELETE FROM fo_reservation_request
WHERE request_id = 'ORDERCARE-M05-REQUEST';

DELETE FROM fo_user_reservation_quota
WHERE id = 9000000000000502
   OR stock_item_id = 9000000000000501;

DELETE FROM fo_stock_item
WHERE id = 9000000000000501
   OR stock_item_code = 'ORDERCARE-M05-STOCK';

SELECT 'OrderCare M0.5 fixture cleaned' AS cleanup_status;
