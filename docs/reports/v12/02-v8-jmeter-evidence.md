# V12 V8 压测证据摘要

来源：JMeter 截图与 V8 压测记录。

## 压测配置

```text
接口：POST /api/reservation/create/v8
线程数：100
Ramp-up：10s
Loop：9
总请求数：900
请求体：userId=1001, resourceId=1, stockItemId=1, quantity=1, requestId=jm-${__UUID}
```

## JMeter 结果

| 指标 | 结果 |
| --- | ---: |
| Samples | 900 |
| Average | 16 ms |
| Min | 4 ms |
| Max | 213 ms |
| Std.Dev | 22.69 |
| Error % | 0.00% |
| Throughput | 91.4/sec |

## SQL 核对结论

此前 V8 压测后 SQL 核对结果：

```text
fo_reservation_request：900 条成功
fo_reservation_order：900 条 RESERVED
fo_stock_deduct_record：900 条 ORDER_CREATED
fo_mq_outbox：创建命令和结果消息均 SENT
库存恒等式 diff=0
```

## 可用于简历的结论

```text
设计持久化异步预约引擎，将用户请求先落库，再由数据库租约和有界线程池异步处理；JMeter 100 并发、900 请求下 HTTP 错误率 0%，请求、订单、库存预扣和消息状态最终收敛。
```
