# V8 故障验证记录

## 1. 测试说明

本文记录 V8 持久化异步预约链路的故障验证过程。

验证目标：

- V8 请求先持久化，避免依赖 JVM 内存队列保存唯一请求副本。
- 依赖服务短暂不可用时，不破坏库存不变量。
- RabbitMQ / order-service / Redis / resource-service 异常时，系统能快速失败、保留可恢复状态或最终收敛。
- 每个结论均以接口响应、SQL、日志或中间件状态为依据。

## 2. 场景一：order-service 停止/恢复

### 2.1 测试目标

验证 order-service 不可用时：

- V8 请求仍可落库并完成资源侧库存预扣；
- 创建订单命令 Outbox 不丢失；
- 库存保持锁定，不被错误释放；
- order-service 恢复后，RabbitMQ 中的创建命令继续被消费；
- 订单创建结果最终回传 resource-service，库存预扣状态收敛到 `ORDER_CREATED`。

### 2.2 测试前状态

测试前重置库存和用户额度：

```sql
UPDATE fo_stock_item
SET total_stock = 1000,
    available_stock = 1000,
    locked_stock = 0,
    sold_stock = 0
WHERE id = 1;

UPDATE fo_user_reservation_quota
SET limit_quantity = 1000,
    used_quantity = 0
WHERE user_id = 1001
  AND stock_item_id = 1;
```

同时删除 Redis 库存缓存：

```text
DEL floworder:stock:1
```

确认 SQL：

| id | total_stock | available_stock | locked_stock | sold_stock | diff |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 1000 | 0 | 0 | 0 |

| user_id | stock_item_id | limit_quantity | used_quantity |
| ---: | ---: | ---: | ---: |
| 1001 | 1 | 1000 | 0 |

### 2.3 故障注入

停止 order-service：

```powershell
Stop-Process -Id 2624
```

停止后，8082 不再监听。

提交 V8 请求：

```http
POST http://127.0.0.1:8088/api/reservation/create/v8
```

请求体：

```json
{
  "userId": 1001,
  "resourceId": 1,
  "stockItemId": 1,
  "quantity": 1,
  "requestId": "v8-order-down-001"
}
```

接口响应：

```json
{
  "code": 200,
  "message": "success",
  "data": "v8-order-down-001"
}
```

### 2.4 order-service 停止期间中间状态

执行 SQL：

```sql
SELECT request_id, status, order_no, last_error
FROM fo_reservation_request
WHERE request_id = 'v8-order-down-001';

SELECT deduct_no, request_id, status, order_no, last_error
FROM fo_stock_deduct_record
WHERE request_id = 'v8-order-down-001';

SELECT producer_service, message_type, status, retry_count, last_error
FROM fo_mq_outbox
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id = 'v8-order-down-001'
)
ORDER BY producer_service, message_type;

SELECT request_id, order_no, status
FROM fo_reservation_order
WHERE request_id = 'v8-order-down-001';

SELECT id, total_stock, available_stock, locked_stock, sold_stock,
       total_stock - available_stock - locked_stock - sold_stock AS diff
FROM fo_stock_item
WHERE id = 1;
```

结果：

| request_id | status | order_no | last_error |
| --- | ---: | --- | --- |
| v8-order-down-001 | 20 | FO20260626152742190fac7d368 | NULL |

| deduct_no | request_id | status | order_no | last_error |
| --- | --- | ---: | --- | --- |
| FD20260626152742190fc507295 | v8-order-down-001 | 10 | FO20260626152742190fac7d368 | NULL |

| producer_service | message_type | status | retry_count | last_error |
| --- | --- | ---: | ---: | --- |
| floworder-resource-service | ORDER_CREATE_COMMAND | 20 | 0 | NULL |

订单表：

```text
无记录
```

库存：

| id | total_stock | available_stock | locked_stock | sold_stock | diff |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 999 | 1 | 0 | 0 |

判断：

- V8 请求已成功落库并处理完成。
- resource-service 已完成库存预扣。
- 创建订单命令 Outbox 已发送到 RabbitMQ。
- order-service 停止期间，订单表没有记录，符合预期。
- 库存保持锁定，未被错误释放。
- 库存恒等式通过。

### 2.5 恢复 order-service 后最终状态

用户在 IDEA 中重启 order-service，新监听进程：

```text
PID = 3636
Port = 8082
```

等待 15 秒后执行 SQL：

```sql
SELECT request_id, status, order_no, last_error
FROM fo_reservation_request
WHERE request_id = 'v8-order-down-001';

SELECT deduct_no, request_id, status, order_no, last_error
FROM fo_stock_deduct_record
WHERE request_id = 'v8-order-down-001';

SELECT producer_service, message_type, status, retry_count, last_error
FROM fo_mq_outbox
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id = 'v8-order-down-001'
)
ORDER BY producer_service, message_type;

SELECT request_id, order_no, status
FROM fo_reservation_order
WHERE request_id = 'v8-order-down-001';

SELECT consumer_group, status, COUNT(*) cnt
FROM fo_mq_consume_log
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id = 'v8-order-down-001'
)
GROUP BY consumer_group, status
ORDER BY consumer_group, status;
```

结果：

| request_id | status | order_no | last_error |
| --- | ---: | --- | --- |
| v8-order-down-001 | 20 | FO20260626152742190fac7d368 | NULL |

| deduct_no | request_id | status | order_no | last_error |
| --- | --- | ---: | --- | --- |
| FD20260626152742190fc507295 | v8-order-down-001 | 20 | FO20260626152742190fac7d368 | NULL |

| producer_service | message_type | status | retry_count | last_error |
| --- | --- | ---: | ---: | --- |
| floworder-order-service | ORDER_CREATE_SUCCEEDED | 20 | 0 | NULL |
| floworder-resource-service | ORDER_CREATE_COMMAND | 20 | 0 | NULL |

| request_id | order_no | status |
| --- | --- | ---: |
| v8-order-down-001 | FO20260626152742190fac7d368 | 10 |

| consumer_group | status | cnt |
| --- | ---: | ---: |
| order-create-consumer | 10 | 1 |
| order-result-consumer | 10 | 1 |

库存：

| id | total_stock | available_stock | locked_stock | sold_stock | diff |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 999 | 1 | 0 | 0 |

### 2.6 结论

order-service 停止/恢复场景通过。

可作为证据的结论：

```text
order-service 停止期间，V8 请求仍能完成持久化、库存预扣和创建命令 Outbox 发送；
订单表暂时无记录，库存保持锁定；
order-service 恢复后，RabbitMQ 中的创建命令继续被消费，订单创建结果回传 resource-service，
库存预扣状态从 PRE_DEDUCTED 收敛到 ORDER_CREATED；
全程库存恒等式成立。
```

边界：

- 本场景验证的是订单创建阶段恢复。
- 未覆盖订单确认、取消、超时后的 `SOLD` / `RELEASED` 履约终态。

## 3. 场景二：RabbitMQ 停止/恢复

### 3.1 测试目标

验证 RabbitMQ 不可用时：

- V8 请求仍能先落库；
- resource-service 能完成库存预扣和订单创建命令 Outbox 落库；
- RabbitMQ 发布失败不会丢消息，Outbox 进入 `RETRY`；
- RabbitMQ 恢复后，Outbox 定时任务能够重新发送，订单最终创建成功。

### 3.2 有效链路证据

测试请求：

```json
{
  "userId": 1001,
  "resourceId": 1,
  "stockItemId": 1,
  "quantity": 1,
  "requestId": "v8-rabbit-down-002"
}
```

RabbitMQ 停止期间接口响应：

```json
{
  "code": 200,
  "message": "success",
  "data": "v8-rabbit-down-002"
}
```

RabbitMQ 停止期间 SQL 中间态：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20`，请求已被 V8 异步引擎处理成功 |
| `fo_stock_deduct_record` | `status=10 PRE_DEDUCTED`，库存预扣记录存在 |
| `fo_mq_outbox` | `ORDER_CREATE_COMMAND status=30 RETRY`，`last_error=Connection refused` |
| `fo_reservation_order` | 无订单记录 |
| `fo_stock_item` | `available_stock=999, locked_stock=1, diff=0` |

RabbitMQ 恢复后 SQL 最终状态：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20` |
| `fo_stock_deduct_record` | `status=20 ORDER_CREATED` |
| resource-service `fo_mq_outbox` | `ORDER_CREATE_COMMAND status=20 SENT, retry_count=4` |
| order-service `fo_mq_outbox` | `ORDER_CREATE_SUCCEEDED status=20 SENT` |
| `fo_reservation_order` | `status=10 RESERVED` |
| `fo_mq_consume_log` | `order-create-consumer=1, order-result-consumer=1` |

### 3.3 本次证据限制

本场景恢复过程中，`fo_stock_item` 后续出现过人工 reset 痕迹：

```text
fo_stock_item.updated_at = 2026-06-26 15:40:18
available_stock = 1000
locked_stock = 0
```

该时间早于 RabbitMQ 恢复后的订单创建结果收敛时间，因此本次 RabbitMQ 场景只作为“Outbox 重试与 MQ 恢复链路”证据，不作为最终库存锁定证据。

需要在 resource-service 重启并确认没有外部 reset SQL 干扰后，对 RabbitMQ 停止/恢复重新跑一次库存完整证据。

## 4. 场景三：Redis 停止/恢复

### 4.1 Redis 长时间不可用

测试请求：

```json
{
  "userId": 1001,
  "resourceId": 1,
  "stockItemId": 1,
  "quantity": 1,
  "requestId": "v8-redis-down-001"
}
```

接口响应：

```json
{
  "code": 200,
  "message": "success",
  "data": "v8-redis-down-001"
}
```

Redis 不可用期间 SQL：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=30 RETRY, retry_count=2` |
| `fo_stock_deduct_record` | 无记录 |
| `fo_stock_item` | `available_stock=1000, locked_stock=0, diff=0` |
| `fo_user_reservation_quota` | `used_quantity=0` |

Redis 恢复较晚后 SQL：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=50 MANUAL_REVIEW` |
| `fo_stock_deduct_record` | 无记录 |
| `fo_stock_item` | 库存未扣减 |
| `fo_user_reservation_quota` | 额度未占用 |

结论：Redis 故障持续时间超过 V8 当前重试窗口时，请求不会盲目扣 MySQL 库存，也不会占用额度，最终进入人工确认。

### 4.2 Redis 短时间不可用后恢复

测试请求：

```json
{
  "userId": 1001,
  "resourceId": 1,
  "stockItemId": 1,
  "quantity": 1,
  "requestId": "v8-redis-recover-001"
}
```

恢复后 SQL：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20 SUCCEEDED, retry_count=1` |
| `fo_stock_deduct_record` | `status=20 ORDER_CREATED` |
| resource-service `fo_mq_outbox` | `ORDER_CREATE_COMMAND status=20 SENT` |
| order-service `fo_mq_outbox` | `ORDER_CREATE_SUCCEEDED status=20 SENT` |
| `fo_reservation_order` | `status=10 RESERVED` |
| `fo_mq_consume_log` | `order-create-consumer=1, order-result-consumer=1` |

本次仍观察到库存和额度字段未保持预期占用：

```text
fo_stock_item: available_stock=1000, locked_stock=0
fo_user_reservation_quota: used_quantity=0
```

该结果与 `fo_stock_deduct_record.status=20 ORDER_CREATED`、订单 `RESERVED` 的语义不一致。后续继续故障实验前，需要先重启 resource-service 并复测一条普通 V8 成功链路，确认运行时代码和 SQL reset 没有干扰。

## 5. 当前阻塞点

当前不建议继续执行 resource-service 处理中重启实验。

原因：

1. RabbitMQ 和 Redis 恢复链路均能证明消息/请求状态收敛；
2. 但库存与额度证据出现异常，不能把当前结果写成完整通过；
3. 下一步应先重启 resource-service，重新跑一条普通 V8 成功请求，确认：
   - `fo_stock_deduct_record.status=20 ORDER_CREATED`；
   - `fo_reservation_order.status=10 RESERVED`；
   - `fo_stock_item.available_stock=999, locked_stock=1`；
   - `fo_user_reservation_quota.used_quantity=1`；
   - 库存恒等式 `diff=0`。
