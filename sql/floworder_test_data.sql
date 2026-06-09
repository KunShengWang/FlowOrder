USE floworder;

-- FlowOrder V1 local test data.
-- Test request:
-- POST http://localhost:8081/reservation/create/v1
-- {
--   "userId": 1001,
--   "resourceId": 1,
--   "stockItemId": 1,
--   "quantity": 1,
--   "requestId": "req-v1-001"
-- }

DELETE FROM fo_stock_deduct_record WHERE resource_id = 1 OR stock_item_id = 1;
DELETE FROM fo_reservation_order WHERE resource_id = 1 OR stock_item_id = 1;
DELETE FROM fo_stock_item WHERE id = 1 OR stock_item_code = 'STOCK_GPU_AM_001';
DELETE FROM fo_resource WHERE id = 1 OR resource_code = 'RES_GPU_TEST';

INSERT INTO fo_resource (
    id,
    resource_code,
    name,
    description,
    status,
    deleted
) VALUES (
    1,
    'RES_GPU_TEST',
    'GPU Reservation',
    'FlowOrder V1 local test resource',
    1,
    0
);

INSERT INTO fo_stock_item (
    id,
    stock_item_code,
    resource_id,
    name,
    total_stock,
    available_stock,
    locked_stock,
    sold_stock,
    status,
    start_time,
    end_time,
    version,
    deleted
) VALUES (
    1,
    'STOCK_GPU_AM_001',
    1,
    '2026-06-09 AM Slot',
    10,
    10,
    0,
    0,
    1,
    '2026-06-09 09:00:00',
    '2026-06-09 12:00:00',
    0,
    0
);

SELECT 'fo_resource' AS table_name, id, resource_code, name, status, deleted
FROM fo_resource
WHERE id = 1;

SELECT 'fo_stock_item' AS table_name, id, stock_item_code, resource_id, total_stock, available_stock, status, deleted
FROM fo_stock_item
WHERE id = 1;
