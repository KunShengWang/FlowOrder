USE floworder;

-- FlowOrder V1 JMeter load-test data.
-- This dataset uses a large stock value so the test measures throughput instead
-- of mostly measuring "stock not enough" business failures.

DELETE FROM fo_stock_deduct_record WHERE resource_id = 1 OR stock_item_id = 1;
DELETE FROM fo_reservation_order WHERE resource_id = 1 OR stock_item_id = 1;
DELETE FROM fo_user_reservation_quota WHERE resource_id = 1 OR stock_item_id = 1;
DELETE FROM fo_stock_item WHERE id = 1 OR stock_item_code = 'STOCK_GPU_JMETER_001';
DELETE FROM fo_resource WHERE id = 1 OR resource_code = 'RES_GPU_JMETER';

INSERT INTO fo_resource (
    id,
    resource_code,
    name,
    description,
    status,
    deleted
) VALUES (
    1,
    'RES_GPU_JMETER',
    'GPU Reservation JMeter',
    'FlowOrder V1 JMeter test resource',
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
    'STOCK_GPU_JMETER_001',
    1,
    'JMeter Load Slot',
    10000,
    10000,
    0,
    0,
    1,
    DATE_SUB(NOW(), INTERVAL 1 HOUR),
    DATE_ADD(NOW(), INTERVAL 1 DAY),
    0,
    0
);

SELECT 'fo_resource' AS table_name, id, resource_code, name, status, deleted
FROM fo_resource
WHERE id = 1;

SELECT 'fo_stock_item' AS table_name, id, stock_item_code, resource_id, total_stock, available_stock, status, deleted
FROM fo_stock_item
WHERE id = 1;

INSERT INTO fo_user_reservation_quota (
    id,
    resource_id,
    stock_item_id,
    user_id,
    status,
    limit_quantity,
    used_quantity,
    valid_from,
    valid_until,
    version
) VALUES (
     1,
     1,
     1,
     1001,
     1,
     10000,
     0,
     DATE_SUB(NOW(), INTERVAL 1 HOUR),
     DATE_ADD(NOW(), INTERVAL 1 DAY),
     0
);
