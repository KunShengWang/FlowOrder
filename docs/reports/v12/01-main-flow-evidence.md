# V12 主链路证据

时间：2026-06-26

## 1. 确认成交链路

请求：

```text
POST /api/reservation/create/v8
requestId = v12-confirm-164232
```

随后调用：

```text
POST /api/order/confirm
orderNo = FO20260626164235285d9b208ae
```

SQL 结果：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20`, `order_status=20`, `latest_order_event_type=ORDER_CONFIRMED`, `order_event_version=1` |
| `fo_reservation_order` | `status=20` |
| `fo_stock_deduct_record` | `status=60 SOLD` |
| `fo_stock_item` | `total=1000`, `available=999`, `locked=0`, `sold=1`, `diff=0` |
| `fo_user_reservation_quota` | `used_quantity=1` |

结论：预约请求、订单、库存预扣记录和库存字段均收敛到“已确认成交”。

## 2. 取消释放链路

请求：

```text
POST /api/reservation/create/v8
requestId = v12-cancel-164232
```

随后调用：

```text
POST /api/order/cancel
orderNo = FO20260626164252114deca6d70
reason = v12 evidence cancel
```

SQL 结果：

| 表 | 关键结果 |
| --- | --- |
| `fo_reservation_request` | `status=20`, `order_status=30`, `latest_order_event_type=ORDER_CANCELLED`, `order_event_version=1` |
| `fo_reservation_order` | `status=30`, `cancel_reason=v12 evidence cancel` |
| `fo_stock_deduct_record` | `status=30 RELEASED` |
| `fo_stock_item` | `total=1000`, `available=1000`, `locked=0`, `sold=0`, `diff=0` |
| `fo_user_reservation_quota` | `used_quantity=0` |

结论：取消后库存和额度均释放，订单、请求和库存记录状态一致。

## 3. 可用于简历的结论

FlowOrder 已完成：

```text
异步预约受理 -> 库存预扣 -> MQ 下单 -> 订单确认/取消 -> 库存 SOLD/RELEASED -> requestId 查询最终履约状态
```

当前证据支持描述为：

```text
基于状态机和 MQ 事件驱动实现预约交易履约闭环，确认订单时锁定库存转成交库存，取消订单时释放库存和用户额度，并通过 SQL 核对库存恒等式。
```
