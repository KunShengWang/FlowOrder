DELETE FROM fo_mq_dead_letter
WHERE message_id LIKE 'IC-HAPPY-%'
   OR message_id LIKE 'IC-CONFLICT-%'
   OR message_id LIKE 'IC-MQTIMEOUT-%';

DELETE FROM fo_reservation_order
WHERE request_id LIKE 'IC-HAPPY-%'
   OR request_id LIKE 'IC-CONFLICT-%'
   OR request_id LIKE 'IC-MQTIMEOUT-%';

DELETE FROM fo_stock_deduct_record
WHERE request_id LIKE 'IC-HAPPY-%'
   OR request_id LIKE 'IC-CONFLICT-%'
   OR request_id LIKE 'IC-MQTIMEOUT-%';

DELETE FROM fo_reservation_request
WHERE request_id LIKE 'IC-HAPPY-%'
   OR request_id LIKE 'IC-CONFLICT-%'
   OR request_id LIKE 'IC-MQTIMEOUT-%';

DELETE FROM fo_stock_item
WHERE stock_item_code IN ('IC-HAPPY-STOCK', 'IC-CONFLICT-STOCK', 'IC-MQTIMEOUT-STOCK');

SELECT 'Incident Command fixtures cleaned' AS cleanup_status;
