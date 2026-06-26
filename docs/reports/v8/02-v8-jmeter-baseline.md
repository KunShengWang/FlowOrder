# V8 JMeter 基线压测记录

## 1. 测试结论

本轮测试可以作为 V8 入口受理能力的第一份证据，但不能作为 V8 完整闭环通过证据。

结论如下：

- V8 入口 HTTP 层通过：900 次请求，错误率 0%。
- V8 请求状态收敛：`fo_reservation_request` 中 5 条成功，895 条业务失败，无 `PENDING`、`PROCESSING`、`RETRY`、`MANUAL_REVIEW` 残留。
- 用户额度限购生效：用户 `1001` 在库存项 `1` 上 `limit_quantity=5`、`used_quantity=5`，未超额度。
- MQ 主链路已产生发送成功记录：resource-service 创建命令 Outbox 5 条 `SENT`，order-service 结果 Outbox 5 条 `SENT`。
- 库存恒等式未通过：`fo_stock_item.id=1` 当前 `total_stock=1000, available_stock=5, locked_stock=5, sold_stock=0`，`diff=990`。该问题倾向于压测前测试数据初始化不满足库存恒等式，不能把本轮作为库存不变量通过证据。

本轮测试状态：部分通过，需要修正测试数据后复测库存不变量。

## 2. 测试目标

验证 V8 持久化异步预约入口在并发请求下的基础受理能力，并通过 SQL 核对：

- V8 请求状态是否最终收敛；
- 用户额度是否被并发超用；
- 库存字段是否满足不变量；
- 库存预扣记录是否与成功请求对应；
- Outbox 是否生成并发送成功。

## 3. 测试接口与压测参数

测试接口：

```http
POST /api/reservation/create/v8
```

JMeter 请求体：

```json
{
  "userId": 1001,
  "resourceId": 1,
  "stockItemId": 1,
  "quantity": 1,
  "requestId": "jm-${__UUID}"
}
```

JMeter 参数：

| 参数 | 值 |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `8088` |
| Threads | `100` |
| Ramp-up | `10s` |
| Loop Count | `9` |
| Total Samples | `900` |
| requestId | `jm-${__UUID}` |

## 4. JMeter 结果

| 指标 | 结果 |
| --- | ---: |
| Samples | 900 |
| Average | 16 ms |
| Min | 4 ms |
| Max | 213 ms |
| Std. Dev. | 22.69 |
| Error % | 0.00% |
| Throughput | 91.4/sec |
| Received | 22.74 KB/sec |
| Sent | 27.58 KB/sec |
| Avg. Bytes | 254.8 |

说明：

- 本次截图中的 JMeter 视图没有 P95/P99 字段。
- 后续正式性能报告应使用 Aggregate Report 或导出 `.jtl` 后计算 P90/P95/P99。

## 5. SQL 核对结果

### 5.1 V8 请求状态分布

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_reservation_request
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 20 | 5 |
| 40 | 895 |

状态含义：

| status | 含义 |
| ---: | --- |
| 20 | V8 请求处理成功 |
| 40 | V8 请求业务失败 |

判断：

- 无 `0 PENDING`。
- 无 `10 PROCESSING`。
- 无 `30 RETRY`。
- 无 `50 MANUAL_REVIEW`。
- 本轮 V8 请求状态已收敛。

### 5.2 V8 请求总数

执行 SQL：

```sql
SELECT COUNT(*) AS total_requests
FROM fo_reservation_request
WHERE request_id LIKE 'jm-%';
```

结果：

| total_requests |
| ---: |
| 900 |

判断：

- 与 JMeter `Samples=900` 一致。
- `requestId=jm-${__UUID}` 唯一请求全部落库。

### 5.3 库存不变量

执行 SQL：

```sql
SELECT
    id,
    total_stock,
    available_stock,
    locked_stock,
    sold_stock,
    total_stock - available_stock - locked_stock - sold_stock AS diff
FROM fo_stock_item
WHERE id = 1;
```

结果：

| id | total_stock | available_stock | locked_stock | sold_stock | diff |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 5 | 5 | 0 | 990 |

判断：

- 库存字段均未出现负数。
- 但库存恒等式未通过：`total_stock != available_stock + locked_stock + sold_stock`。
- 当前结果不能证明库存不变量通过。
- 结合本轮成功请求数仅 5 条、锁定库存为 5，问题更像是压测前 `fo_stock_item` 测试数据初始化不一致，例如 `total_stock=1000` 但可用库存只重置为较小值。

后续修正测试数据后必须复测。

### 5.4 库存预扣记录

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_stock_deduct_record
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 20 | 5 |

判断：

- 只有 5 条请求真正进入库存预扣和异步下单链路。
- 与用户额度 `limit_quantity=5` 对应。
- 其余 895 条在 V8 处理阶段因额度不足失败，没有生成库存预扣记录。

### 5.5 Outbox 状态

执行 SQL：

```sql
SELECT producer_service, status, COUNT(*) AS cnt
FROM fo_mq_outbox
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id LIKE 'jm-%'
)
GROUP BY producer_service, status
ORDER BY producer_service, status;
```

结果：

| producer_service | status | cnt |
| --- | ---: | ---: |
| floworder-order-service | 20 | 5 |
| floworder-resource-service | 20 | 5 |

判断：

- resource-service 订单创建命令 Outbox 已发送成功 5 条。
- order-service 订单创建结果 Outbox 已发送成功 5 条。
- 本轮没有观察到 `RETRY` 或 `DEAD`。

### 5.6 用户额度

执行 SQL：

```sql
SELECT
    user_id,
    stock_item_id,
    limit_quantity,
    used_quantity,
    used_quantity - limit_quantity AS over_used
FROM fo_user_reservation_quota
WHERE user_id = 1001
  AND stock_item_id = 1;
```

结果：

| user_id | stock_item_id | limit_quantity | used_quantity | over_used |
| ---: | ---: | ---: | ---: | ---: |
| 1001 | 1 | 5 | 5 | 0 |

判断：

- 并发压测下用户额度未被超用。
- 895 条业务失败的主要原因是 `用户预约额度不足`。

### 5.7 订单状态

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_reservation_order
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 10 | 5 |

判断：

- 5 条成功进入订单链路。
- 当前订单状态为 `10 RESERVED`，说明订单已创建但未进入确认、取消或超时终态。
- V8 的 `status=20` 表示预约请求已成功受理并进入异步下单链路，不等于订单履约最终完成。

## 6. 本轮通过项

- V8 接口在 900 次并发请求下 HTTP 错误率为 0%。
- V8 请求全部落库，数量与 JMeter 样本数一致。
- V8 请求状态最终收敛，无处理卡死。
- 用户额度没有被并发超用。
- 只有额度内 5 个请求进入库存预扣和订单链路。
- resource-service 与 order-service Outbox 均出现成功发送记录。

## 7. 本轮未通过项与风险

### 7.1 库存恒等式未通过

当前库存：

```text
total_stock = 1000
available_stock = 5
locked_stock = 5
sold_stock = 0
```

计算：

```text
1000 - 5 - 5 - 0 = 990
```

这说明当前测试数据无法证明库存不变量。

本轮不能写成：

```text
V8 并发压测验证库存不变量通过。
```

只能写成：

```text
V8 并发压测验证入口受理、请求状态收敛和用户额度限购有效；库存不变量因测试数据初始化不一致需修正后复测。
```

### 7.2 缺少 P95/P99

当前 JMeter 截图没有 P95/P99。

后续正式压测需要补：

- P90；
- P95；
- P99；
- 错误率；
- Throughput；
- RabbitMQ 积压；
- 线程池活跃数和队列长度。

### 7.3 未覆盖故障场景

本轮没有覆盖：

- RabbitMQ 停止/恢复；
- Redis 停止；
- order-service 不可用；
- resource-service 处理中停止重启；
- V8 worker 队列满后的租约释放与重试。

这些应进入后续 V8/V12 故障验证报告。

## 8. 下一步建议

下一轮先修正测试数据，再重跑一次 V8 JMeter。

建议压测前准备数据满足：

```text
total_stock = available_stock + locked_stock + sold_stock
```

例如：

```text
total_stock = 1000
available_stock = 1000
locked_stock = 0
sold_stock = 0
```

同时将用户额度调大，例如：

```text
limit_quantity = 1000
used_quantity = 0
```

然后重跑：

- 100 线程；
- Ramp-up 10s；
- Loop 9；
- 900 条唯一 requestId。

复测目标：

- V8 请求无卡死；
- 库存不为负；
- 额度不超；
- 库存恒等式通过；
- 成功数、失败数和库存变化能对上；
- Outbox 无异常积压。

## 9. 二次复查记录

复查时间：2026-06-26

二次复查时，`jm-%` 压测数据已经发生变化，说明测试数据已被重置并重新执行过压测。

### 9.1 V8 请求状态

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_reservation_request
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 20 | 900 |

判断：

- 900 条 V8 请求全部处理成功。
- 无 `PENDING`、`PROCESSING`、`RETRY`、`FAILED`、`MANUAL_REVIEW` 残留。
- V8 请求受理与处理状态已收敛。

### 9.2 库存不变量

执行 SQL：

```sql
SELECT
    id,
    total_stock,
    available_stock,
    locked_stock,
    sold_stock,
    total_stock - available_stock - locked_stock - sold_stock AS diff
FROM fo_stock_item
WHERE id = 1;
```

结果：

| id | total_stock | available_stock | locked_stock | sold_stock | diff |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 100 | 900 | 0 | 0 |

判断：

- 库存恒等式通过。
- 当前压测后库存没有出现负数。
- 900 条请求成功预扣后，库存从可用态转入锁定态，符合 V8 + V3 异步下单阶段语义。

### 9.3 用户额度

执行 SQL：

```sql
SELECT
    user_id,
    stock_item_id,
    limit_quantity,
    used_quantity,
    used_quantity - limit_quantity AS over_used
FROM fo_user_reservation_quota
WHERE user_id = 1001
  AND stock_item_id = 1;
```

结果：

| user_id | stock_item_id | limit_quantity | used_quantity | over_used |
| ---: | ---: | ---: | ---: | ---: |
| 1001 | 1 | 1000 | 900 | -100 |

判断：

- 用户额度未被超用。
- `used_quantity=900` 与 V8 成功请求数一致。

### 9.4 库存预扣状态

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_stock_deduct_record
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 10 | 875 |
| 20 | 25 |

判断：

- 900 条请求都进入了库存预扣链路。
- 875 条仍处于 `PRE_DEDUCTED`。
- 25 条已进入 `ORDER_CREATED`。
- 说明 V8 入口和资源侧预扣已经完成，但订单创建消费链路尚未完全收敛。

### 9.5 Outbox 与订单消费

执行 SQL：

```sql
SELECT producer_service, status, COUNT(*) AS cnt
FROM fo_mq_outbox
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id LIKE 'jm-%'
)
GROUP BY producer_service, status
ORDER BY producer_service, status;
```

结果：

| producer_service | status | cnt |
| --- | ---: | ---: |
| floworder-order-service | 20 | 25 |
| floworder-resource-service | 20 | 900 |

补充 SQL：

```sql
SELECT COUNT(*) AS consume_logs
FROM fo_mq_consume_log
WHERE message_id IN (
    SELECT message_id
    FROM fo_mq_outbox
    WHERE producer_service = 'floworder-resource-service'
      AND biz_key IN (
          SELECT deduct_no
          FROM fo_stock_deduct_record
          WHERE request_id LIKE 'jm-%'
      )
);
```

结果：

| consume_logs |
| ---: |
| 25 |

判断：

- resource-service 900 条订单创建命令 Outbox 均已标记为 `SENT`。
- order-service 当前只成功消费了 25 条创建命令，并生成 25 条订单结果 Outbox。
- 这说明当前瓶颈或异常点不在 V8 请求入口，而在订单创建 MQ 消费收敛链路。

### 9.6 订单状态

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_reservation_order
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 10 | 25 |

判断：

- 已创建订单 25 条。
- 其余 875 条请求暂未在订单表形成记录。

## 10. 二次复查结论

二次复查相比第一次已有明显进展：

- 库存测试数据已修正。
- V8 900 条请求全部成功。
- 库存恒等式通过。
- 用户额度未超用。
- resource-service Outbox 900 条创建命令均已发送成功。

但 V8 完整闭环仍不能判定通过，原因是：

- order-service 只消费成功 25 条创建命令；
- `fo_stock_deduct_record` 中仍有 875 条停留在 `PRE_DEDUCTED`；
- 订单表只有 25 条对应订单；
- 暂未看到这些未消费命令进入 DLQ 或死信表。

当前阶段可以得出的结论：

```text
V8 持久化预约请求入口、数据库租约、有界线程池处理、额度占用和资源侧 Outbox 发送在本轮压测中表现正常；
库存恒等式通过；
但订单创建 MQ 消费链路尚未完全收敛，需要继续排查 RabbitMQ 队列积压、order-service 消费者状态和消费异常日志。
```

下一步优先排查：

1. RabbitMQ `floworder.order.create.queue` 是否积压。
2. order-service `OrderCreateConsumer` 是否仍在运行。
3. order-service 日志中是否有消费异常或线程池阻塞。
4. `fo_mq_consume_log` 为什么只有 25 条创建命令消费记录。
5. 未消费的 875 条消息是在 RabbitMQ 队列中、已丢失，还是 order-service 消费失败但未进入 DLQ。

## 11. 三次复查记录：订单创建链路最终收敛

复查时间：2026-06-26

在二次复查后继续观察，订单创建消费链路最终完成收敛。

### 11.1 库存预扣记录

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_stock_deduct_record
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 20 | 900 |

判断：

- 900 条库存预扣记录均从 `PRE_DEDUCTED` 收敛到 `ORDER_CREATED`。
- 资源侧已经全部收到订单创建成功结果。

### 11.2 Outbox 状态

执行 SQL：

```sql
SELECT producer_service, status, COUNT(*) AS cnt
FROM fo_mq_outbox
WHERE biz_key IN (
    SELECT deduct_no
    FROM fo_stock_deduct_record
    WHERE request_id LIKE 'jm-%'
)
GROUP BY producer_service, status
ORDER BY producer_service, status;
```

结果：

| producer_service | status | cnt |
| --- | ---: | ---: |
| floworder-order-service | 20 | 900 |
| floworder-resource-service | 20 | 900 |

判断：

- resource-service 创建命令 Outbox 900 条均为 `SENT`。
- order-service 创建结果 Outbox 900 条均为 `SENT`。

### 11.3 消费日志与订单状态

执行 SQL：

```sql
SELECT COUNT(*) AS order_create_consume_logs
FROM fo_mq_consume_log
WHERE consumer_group = 'order-create-consumer';
```

结果：

| order_create_consume_logs |
| ---: |
| 900 |

执行 SQL：

```sql
SELECT status, COUNT(*) AS cnt
FROM fo_reservation_order
WHERE request_id LIKE 'jm-%'
GROUP BY status
ORDER BY status;
```

结果：

| status | cnt |
| ---: | ---: |
| 10 | 900 |

判断：

- order-service 已消费 900 条订单创建命令。
- 订单表中生成 900 条预约订单，当前状态均为 `RESERVED`。
- V8 到订单创建阶段已经完成收敛。

### 11.4 最终结论更新

本轮 V8 JMeter 基线压测最终结论更新为：

```text
在 100 线程、900 次唯一 requestId 请求下，V8 入口 HTTP 错误率为 0%，900 条请求全部落库并处理成功；
库存恒等式通过，用户额度未超用；
resource-service 创建命令 Outbox、order-service 创建结果 Outbox 均最终发送成功；
订单创建消费日志和订单表均达到 900 条，V8 到订单创建阶段最终收敛。
```

边界说明：

- 本轮验证的是 V8 请求受理、库存预扣和订单创建阶段。
- 订单当前状态为 `RESERVED`，尚未验证订单确认、取消、超时后的 `SOLD` / `RELEASED` 履约终态。
- 压测期间曾观察到 order-service Rabbit listener 启动异常：`Consumer failed to start in 60000 milliseconds; does the task executor have enough threads to support the container concurrency?`。虽然本轮最终收敛，但该异常说明订单创建消费者线程池配置仍存在稳定性风险，需要单独修复或降级配置后复测。
