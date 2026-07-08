# V12-04 订单超时关闭证据

验证时间：2026-07-08

## 1. 验证目标

验证订单从 `RESERVED` 超时关闭后，订单、预约请求、库存预扣记录、库存字段、用户额度和 MQ 状态能够最终收敛。

状态期望：

| 对象 | 期望结果 |
| --- | --- |
| `fo_reservation_order` | `status=40 TIMEOUT` |
| `fo_reservation_request` | `order_status=40`, `latest_order_event_type=ORDER_TIMEOUT` |
| `fo_stock_deduct_record` | `status=30 RELEASED` |
| `fo_stock_item` | `available_stock` 释放，`locked_stock=0`，库存恒等式 `diff=0` |
| `fo_user_reservation_quota` | `used_quantity=0` |
| MQ | 超时状态事件 Outbox 发送成功，消费日志成功 |

## 2. 测试请求

```text
POST /api/reservation/create/v8

requestId = v12-timeout-130557
userId = 1001
resourceId = 1
stockItemId = 1
quantity = 1
```

接口返回：

```json
{"code":200,"message":"success","data":"v12-timeout-130557"}
```

说明：为缩短验证时间，订单创建成功后通过 SQL 将该测试订单的 `expire_time` 调整为已过期时间，再等待 `OrderTimeoutTask` 扫描处理。

## 3. 超时前状态

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20`, `order_no=FO202607081305582675be58815`, `order_status=10`, `order_event_version=0` |
| `fo_reservation_order` | `status=10 RESERVED`, `expire_time=2026-07-08 13:20:58`, `version=0` |
| `fo_stock_deduct_record` | `deduct_no=FD20260708130558267a0909d22`, `status=20 ORDER_CREATED` |
| `fo_stock_item` | `total=1000`, `available=999`, `locked=1`, `sold=0`, `diff=0` |
| `fo_user_reservation_quota` | `used_quantity=1` |

## 4. 超时后状态

等待超时扫描、订单状态 Outbox 发送、resource-service 消费完成后，SQL 结果：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20`, `order_status=40`, `latest_order_event_type=ORDER_TIMEOUT`, `order_event_version=1` |
| `fo_reservation_order` | `status=40`, `canceled_at=2026-07-08 13:06:24`, `cancel_reason=订单超时自动关闭`, `version=1` |
| `fo_stock_deduct_record` | `status=30`, `release_reason=订单取消或超时` |
| `fo_stock_item` | `total=1000`, `available=1000`, `locked=0`, `sold=0`, `diff=0` |
| `fo_user_reservation_quota` | `used_quantity=0` |

## 5. MQ 与日志证据

订单状态日志：

| 事件 | 状态变化 | 操作方 | 备注 |
| --- | --- | --- | --- |
| `CREATE` | `0 -> 10` | `SYSTEM` | 订单创建成功 |
| `TIMEOUT` | `10 -> 40` | `SYSTEM` | 订单超时自动关闭 |

Outbox：

| message_type | biz_key | status | retry_count |
| --- | --- | --- | --- |
| `ORDER_CREATE_COMMAND` | `FD20260708130558267a0909d22` | `20 SENT` | `0` |
| `ORDER_CREATE_SUCCEEDED` | `FD20260708130558267a0909d22` | `20 SENT` | `0` |
| `ORDER_TIMEOUT` | `FO202607081305582675be58815` | `20 SENT` | `0` |

消费日志：

| consumer_group | message_type | biz_key | status |
| --- | --- | --- | --- |
| `order-create-consumer` | `ORDER_CREATE_COMMAND` | `FD20260708130558267a0909d22` | `10 SUCCESS` |
| `order-result-consumer` | `ORDER_CREATE_SUCCEEDED` | `FD20260708130558267a0909d22` | `10 SUCCESS` |
| `order-state-consumer` | `ORDER_TIMEOUT` | `FD20260708130558267a0909d22` | `10 SUCCESS` |

死信检查：

```text
request_dead_letter_count = 0
```

## 6. 结论

超时关闭链路已完成端到端验证：

```text
RESERVED 订单过期
-> OrderTimeoutTask 扫描关闭
-> 订单状态变更为 TIMEOUT
-> 写订单状态日志
-> 写 ORDER_TIMEOUT Outbox
-> RabbitMQ 投递状态事件
-> resource-service 消费事件
-> 库存预扣记录 RELEASED
-> locked_stock 释放回 available_stock
-> 用户额度释放
-> 预约请求回写 order_status=40
```

该证据支持简历中描述：

```text
建立订单履约状态机，订单确认时锁定库存转成交库存，订单取消或超时时释放库存和用户额度，并通过 MQ 事件驱动资源侧最终一致。
```
