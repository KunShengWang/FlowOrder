# FlowOrder Outbox 发布链路容量改造设计

> 文档状态：待评审，不代表已经实现。
> 设计基线：`0f2cf8a6bd9fbe51205bb332088e7b1d683268f1`（`feat: complete instant reservation acceptance flow`）。
> 范围：只设计 resource-service 与 order-service 的 Outbox 发布容量改造；不改变业务事务、消息协议、消费状态机、DLQ 与 Instant 语义。
> 证据边界：本文引用的 50 并发数据来自 `benchmark/reports/20260825-smoke-gate-report.md`。该轮是冒烟闸门失败后的定位数据，且 JMeter 与服务同机、CPU 已饱和，不能作为简历性能结论。

## 1. 当前瓶颈摘要

Instant 的 HTTP 线程在 MySQL 本地事务中完成额度占用、库存预扣、预扣记录、Outbox 和预约请求 `ACCEPTED` 后即可返回；订单创建和结果回传依赖后续两段 Outbox。入口受理与后台履约因此是两个独立容量面。

当前 resource-service 与 order-service 都采用同一种串行发布模型：

```text
单个 fixed-delay 定时方法
  -> 回收过期 SENDING
  -> 查询最多 100 条 NEW/RETRY
  -> for 循环逐条条件 claim
  -> 单线程 publish
  -> 每条同步等待 Publisher Confirm（最多 5 秒）
  -> SENT 或 RETRY/DEAD
  -> 整批结束后再等待 1000 ms
```

这使单个 Confirm 的等待时间直接进入整批关键路径。`ThreadPoolTaskScheduler` 当前虽配置为 2 个线程，但同一个 fixed-delay 方法不会因此自动重入并发；单纯增大 scheduler pool 不能把串行发送变成并行发送。

## 2. Benchmark 证据

保留的 Before 定位基线如下：

| 指标 | Before |
|---|---:|
| Git 业务基线 | `0f2cf8a...` |
| Instant 并发/持续时间 | 50 / 30 s |
| HTTP 请求/成功 | 11,989 / 11,989 |
| HTTP P95 | 322 ms |
| HTTP 入口吞吐 | 约 399 QPS |
| Outbox 实际消化 | 约 39～40 条/s |
| fulfillment P50/P95/P99/Max | 129/221/229/232 s |
| CPU 平均/峰值 | 约 96.5% / 100% |
| Hikari active/pending 峰值 | 10 / 46 |
| request/预扣/订单 | 11,989 / 11,989 / 11,989 |
| 超卖/重复扣减/重复订单 | 0 / 0 / 0 |
| 库存守恒 | `1,000,000 = 988,011 + 11,989 + 0` |

原报告记录的执行基点是 `a360a8b...` 加当时未提交的 Instant/V7/V8 工作区改动；这些业务改动随后形成了 `0f2cf8a...`。后续 Before/After 对比必须同时记录完整 commit、配置、数据集和原始采样，不能覆盖原报告或原始资产。

该轮证明的是“串行 Outbox 容量与入口存在接近 10 倍差距，并导致长时间积压”；它不能证明 399 QPS 是稳定系统容量，因为 CPU、Hikari 和同机 JMeter 已处于不健康饱和状态。

## 3. 当前源码调用链

### 3.1 resource-service：订单创建命令

1. `ResourceOrderServiceImpl.createV3()` / `createInstantAfterAdmission()` 构造固定 `messageId` 的 `OrderCreateMessage` 和 `MqOutboxEntity`。
2. `StockDeductServiceImpl.preDeductAndSaveOutbox()` 或 `preDeductAndSaveOutboxAndAcceptRequest()` 在同一个本地事务内写额度、预扣记录、库存、Outbox；Instant 还在同一事务内把请求改为 `ACCEPTED`。
3. `MqOutboxPublishTask.publish()` 每轮先调用 `reclaimExpiredClaims()`，再 `findSendable(100)`，逐条 `claim(id)` 后调用 `OutboxMessagePublisher.publish()`。
4. `OutboxMessagePublisher` 通过 `RabbitTemplate.send()` 发布持久化消息，等待 `CorrelationData` Confirm 最多 5 秒；ACK 且无 Return 才 `markSent()`，其他结果 `markFailed()`。
5. order-service 的 `OrderCreateConsumer` 手动 ACK；业务处理、消费日志、订单和订单结果 Outbox 在本地事务内收敛。

关键源码：

- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/service/impl/ResourceOrderServiceImpl.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/service/impl/StockDeductServiceImpl.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/mq/task/MqOutboxPublishTask.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/mq/publisher/OutboxMessagePublisher.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/mq/service/impl/MqOutboxServiceImpl.java`

### 3.2 order-service：订单结果与订单状态事件

1. `OrderCreateMessageServiceImpl.consume()` 在同一事务内写订单、`fo_mq_consume_log` 和订单结果 Outbox；明确业务失败由 `recordFailure()` 写失败结果 Outbox。
2. `OrderStateServiceImpl` 在订单状态事务中写订单状态事件 Outbox。
3. `OrderResultOutboxPublishTask.publish()` 与资源侧相同：回收、查 100 条、逐条 claim、串行 publish。
4. `OrderResultOutboxPublisher` 同样逐条等待 5 秒 Confirm。
5. resource-service 的 `OrderResultConsumer` / `OrderStateConsumer` 在领域事务处理后清缓存、解决对应消费死信并手动 ACK。

关键源码：

- `floworder-server/floworder-order-service/src/main/java/com/javaup/mq/service/impl/OrderCreateMessageServiceImpl.java`
- `floworder-server/floworder-order-service/src/main/java/com/javaup/service/impl/OrderStateServiceImpl.java`
- `floworder-server/floworder-order-service/src/main/java/com/javaup/mq/task/OrderResultOutboxPublishTask.java`
- `floworder-server/floworder-order-service/src/main/java/com/javaup/mq/publisher/OrderResultOutboxPublisher.java`
- `floworder-server/floworder-order-service/src/main/java/com/javaup/mq/service/impl/MqOutboxServiceImpl.java`

### 3.3 现有公共约束

- 两侧 RabbitMQ 均配置 `publisher-confirm-type: correlated`、`publisher-returns: true`、`template.mandatory: true`。
- `fo_mq_outbox.message_id` 唯一，且 `(producer_service, biz_key, message_type)` 唯一。
- `fo_mq_consume_log` 以 `(message_id, consumer_group)` 唯一，订单创建、订单结果、订单状态消费者都以此作为消息消费幂等防线。
- `fo_reservation_request.request_id`、`fo_stock_deduct_record.request_id`、`fo_reservation_order.request_id` 均有唯一约束；订单还有 `order_no`、`deduct_no` 唯一约束。
- 两侧 Outbox 共用 `fo_mq_outbox`，用 `producer_service` 隔离扫描与租约回收。
- application.yaml 未显式配置 Hikari `maximumPoolSize`；实测 active 峰值 10 与 Hikari 默认上限 10 一致，但后续仍应从运行时配置/Actuator确认。

## 4. 当前 Outbox 状态机

```text
NEW(0) ----claim----> SENDING(10) ----ACK且无Return----> SENT(20)
                         |
                         +----NACK/Return/timeout/exception----> RETRY(30)
                         |                                         |
                         |                                         +--到期后再次claim
                         |
                         +----claim_until过期----> RETRY(30)

RETRY --失败次数达到5次--> DEAD(40)
DEAD  --人工retry-------> RETRY
SENT  --人工replay-----> RETRY
```

现有 `claim(id)` 已是条件更新：只有 `id` 匹配、状态为 `NEW/RETRY` 且 `next_retry_time <= now` 才改为 `SENDING`。并发线程或多实例即使查到同一候选，也只有一个更新返回 1。因此当前 claim 能避免同一个租约被重复取得。

现有缺口不是“完全没有 claim”，而是租约身份不完整：表内只有 `claim_until`，没有 `claim_owner` 或 claim token。租约过期并被重新 claim 后，旧 worker 若恢复，仍可仅凭 `id + status=SENDING` 执行 `markSent/markFailed`，从而覆盖新租约的结果。

## 5. 当前吞吐瓶颈原因

1. **Confirm 串行化**：每条消息的网络往返/排队时间不能与其他消息重叠。
2. **fixed-delay 批间空窗**：任务完成一批后固定再等 1000 ms；发送越快，批间等待占比越高。
3. **两段 Outbox 串联**：订单创建命令发布慢会延迟订单；订单结果发布慢又继续延迟 fulfillment 收敛。
4. **调度线程池与发布并发混淆**：scheduler pool 只承载调度方法，不等同于 publisher worker。
5. **容量没有背压边界**：当前没有独立发布队列、in-flight 上限、拒绝指标，也无法直接观察 claim/publish/Confirm 各阶段速率。
6. **系统资源已经竞争**：HTTP 事务、Outbox 状态更新、订单消费者事务、结果消费者事务共享 MySQL；同机 JMeter 与服务共享 CPU。只增加发布并发可能把瓶颈推向 CPU、连接池、RabbitMQ 或消费者。

近似上，单侧同步 Confirm 发布上限为：

```text
publish_capacity ~= workers / average_confirm_latency
```

但端到端 fulfillment 上限是 resource publisher、order consumer、order publisher、resource consumer、数据库和 CPU 中的最小值，不能只用发布线程数推算最终 QPS。

## 6. 方案 A：有界多 worker，worker 内同步 Confirm

```text
Scheduled Scanner（单线程即可）
  -> 获取本实例可用 in-flight permit
  -> 查询候选 NEW/RETRY
  -> 条件 claim，写 SENDING + owner + token + until
  -> 提交到专用有界 ThreadPoolExecutor
      -> RabbitTemplate.send
      -> 同步等待 Confirm（超时有界）
      -> ACK且无Return：按 claim token CAS 为 SENT
      -> 其他结果：按 claim token CAS 为 RETRY/DEAD
  -> worker finally 释放 permit
```

特点：

- 多个 worker 可以重叠等待 Confirm，吞吐近似随 worker 数增长，直到碰到下游或资源瓶颈。
- 保留当前 Publisher 的同步、线性控制流，ACK/NACK/Return/timeout 的状态更新容易测试。
- 独立有界 executor 明确发布并发、等待队列和停机边界；scanner scheduler 不再承担发送工作。
- 宕机仍由数据库租约恢复；ACK 后更新 SENT 前宕机仍是允许的重复发布。
- 第一阶段改动集中在任务、claim 状态条件、executor 配置和指标，不要求重写为回调状态机。

## 7. 方案 B：异步发布与多 in-flight Confirm callback

```text
Scheduled Scanner
  -> 有界申请 in-flight slot
  -> 条件 claim
  -> RabbitTemplate.send 后立即返回
  -> CorrelationData Future/callback
      -> Confirm ACK/NACK
      -> Return 关联
      -> 超时调度
      -> 按 claim token 更新 SENT/RETRY
      -> 清理 correlation registry 并释放 slot
```

特点：

- 少量线程即可维持大量 in-flight，Confirm 延迟较高时理论吞吐和线程利用率更好。
- 必须额外维护有界 in-flight registry、相关数据清理、独立超时任务、Return 与 Confirm 的竞态、callback executor、停机排空和迟到 callback 的 fencing。
- callback、租约回收和人工重放可能并发更新同一行，状态测试矩阵明显扩大。
- 若没有 in-flight 上限，Rabbit 客户端内存、相关数据和数据库回调更新都可能失控；“异步”本身不是背压。

## 8. 两方案对比

| 维度 | 方案 A：有界 worker + 同步 Confirm | 方案 B：异步 Confirm callback |
|---|---|---|
| 实现复杂度 | 中；沿用现有线性 Publisher | 高；新增 callback/timeout/registry 状态机 |
| 吞吐潜力 | 中高；受 worker 数与线程成本约束 | 高；适合大量 in-flight |
| Confirm 处理 | 单个 worker 内顺序清晰 | ACK、Return、超时、迟到回调需协调 |
| 宕机恢复 | 复用 `SENDING + lease` | 复用 lease，但还需清理丢失的内存相关数据 |
| 重复发布风险 | at-least-once，明确可控 | 同样 at-least-once，迟到回调增加竞态 |
| 多实例安全 | DB CAS + token + lease | DB CAS + token + lease，要求更严格 |
| 状态管理 | 现有状态机小幅增强 | DB 状态 + 内存 in-flight 双层状态 |
| 改动规模 | 较小 | 较大 |
| 面试解释性 | 强：有界并发、背压、租约、幂等链路直观 | 强但容易过度设计，需解释更多竞态 |
| FlowOrder 当前适配性 | 高 | 中；应由方案 A 仍不足的证据触发 |

两种方案都不能提供 exactly-once，也都必须依赖消费幂等和领域唯一约束。

## 9. 推荐方案

第一阶段推荐 **方案 A：单 scanner + 有界 publisher executor + worker 内同步 Confirm**。resource-service 和 order-service 都采用相同状态语义与配置结构，先从 2 个 worker 起步，再按 4、8 逐级实验。

方案 B 保留为第二阶段候选：只有当方案 A 已证明瓶颈主要是 Confirm 等待、CPU/DB/消费者仍有余量，而继续增加 worker 的线程成本或吞吐收益明显恶化时，才启动异步 Confirm 设计。

## 10. 推荐理由

1. 当前 39～40/s 的直接瓶颈就是单 worker 等待 Confirm；方案 A 能并行重叠等待，命中问题本身。
2. 当前 Publisher 已能正确区分 ACK、NACK、Return、timeout/exception，方案 A 可复用这段线性语义。
3. CPU 已达到 100%、Hikari pending 达 46，当前证据不支持直接引入大规模 in-flight；先用 2/4/8 的有界实验更安全。
4. 现有数据库租约、重试、DEAD、人工重放、消费幂等都可保留，改动面小于 callback 化。
5. 方案 A 更容易构造确定性单测、并发 claim 测试、宕机接管测试和停机测试。
6. 它能形成清晰的工程叙事：测出串行瓶颈，加入有界并发和背压，保持 at-least-once，再用 backlog 稳定性验证，而不是仅“调大线程池”。

## 11. 新状态机

保留 `NEW / SENDING / SENT / RETRY / DEAD`，不增加“已提交到线程池”等持久化状态：

```text
NEW/到期RETRY
  --claim CAS(owner, token, until)--> SENDING

SENDING(token)
  --ACK && no Return && token匹配--> SENT
  --NACK/Return/timeout/exception && token匹配--> RETRY或DEAD
  --executor拒绝 && token匹配--> RETRY（不计发送失败次数）
  --lease到期且token仍匹配--> RETRY（发送结果未知）

DEAD --人工操作CAS--> RETRY
SENT --人工重放CAS--> RETRY
```

状态含义保持不变：`SENT` 仅表示 Broker ACK 且没有 Return，不表示消费者已经完成；`RETRY` 可能包含“明确失败”或“结果未知”；`DEAD` 是生产端发布失败闭环，不等同于 RabbitMQ 消费 DLQ。

## 12. Claim 算法

推荐第一版继续采用“查询候选 + 逐条条件更新”，但引入 permit 和 claim token：

```sql
UPDATE fo_mq_outbox
SET status = 10,
    claim_owner = :instanceId,
    claim_token = :randomToken,
    claim_until = :now + :lease,
    updated_at = :now
WHERE id = :id
  AND producer_service = :producerService
  AND status IN (0, 30)
  AND next_retry_time <= :now;
```

只有 `affected_rows = 1` 才能提交 worker。每个实例只有一个 scanner；多实例允许各自独立扫描，由数据库 claim CAS 仲裁，不增加 Redis 分布式锁。每轮必须先按可用 permit 限制候选数：

```text
queryLimit = min(configuredBatchSize, availablePermits)
```

scanner 的严格顺序为：

1. 读取 `availablePermits`，计算 `queryLimit`；为本轮先取得对应数量的 permit，没有 permit 就结束扫描。
2. 仅查询最多 `queryLimit` 个 candidate；候选少于预留 permit 时立即释放差额。
3. 对每个 candidate 生成唯一 claim token 并执行条件 claim；claim 失败立即释放对应 permit。
4. claim 成功后才 submit executor，禁止 claim 超过本实例 in-flight 容量的消息。
5. executor 拒绝时，以 `id + SENDING + claim_token` 释放为 `RETRY`，不增加 `retry_count`，随后释放 permit。
6. worker 完成状态更新后在 `finally` 释放 permit。

候选查询继续按 `producer_service + status + next_retry_time + id` 排序。第一版不必为了“批量”引入长事务或 `SELECT ... FOR UPDATE`；claim 期间不能持有数据库连接等待 Confirm。若 per-row CAS 后续被实测为数据库瓶颈，再评估带唯一 batch token 的批量 UPDATE + 回查。

## 13. 多实例安全

- **避免重复 claim**：数据库条件更新是最终仲裁；多个线程/实例查到同一候选时只有一个更新成功。
- **`claim_until`：必须**。它是实例崩溃、线程丢失或停机未完成时重新开放处理权的依据。
- **`claim_owner`：建议新增**。它主要用于可观测性、定位卡住实例、停机管理和按实例统计；仅有 owner 不能阻止同一实例的旧线程覆盖新线程。
- **`claim_token` 或 fencing version：必须二选一**。推荐随机 `claim_token`，每次 claim 都更新；`markSent/markFailed/releaseClaim` 必须携带 token。它就是本次租约的 fencing 证据。
- **数值 `version`：非必需**。若已有统一乐观锁框架可以使用递增 `claim_version`；否则 token 已完成同样的陈旧 worker 隔离，不必同时堆叠两套机制。
- **数据库时钟**：多实例租约判断应尽量使用数据库 `NOW(3)`，或至少确保实例时间同步；否则实例时钟漂移会提前回收或延迟回收。
- **每实例一个 scanner**：scanner A 与 scanner B 可以同时工作，数据库 CAS 是最终仲裁。禁止用 Redis 分布式锁把所有实例的 scanner 串行化，否则会重新引入单点扫描容量上限。
- **所有终态操作 fencing**：`markSent`、`markFailed`、`releaseClaim` 以及逐条 `reclaimExpiredClaim` 都必须携带并校验 claim token；reclaimer 先读取过期行的 token，再以 `id + SENDING + token + 已过期` CAS，不能使用不带 token 的全表状态覆盖。

ACK 后、更新 SENT 前进程宕机时，行最终仍是 `SENDING`，租约到期后另一实例会再次发布相同 `messageId`。这属于预期的 at-least-once 重复，不能通过 Outbox 本身消除，也不能宣称 exactly-once。

## 14. 线程池模型

两侧各使用独立、可配置、可优雅停机的 publisher executor，scanner scheduler 保持单线程即可。

建议实验档位：

| 档位 | core=max workers | queue capacity 初值 | max in-flight 初值 | 用途 |
|---|---:|---:|---:|---|
| P2 | 2 | 4 | 6 | 最小改造闸门 |
| P4 | 4 | 8 | 12 | 中档容量 |
| P8 | 8 | 16 | 24 | 首轮上限探索 |

这些是实验档位，不是生产定值。建议使用固定大小 worker（`core=max`），避免在容量实验中混入线程扩缩容变量。队列必须有界；拒绝策略使用 `AbortPolicy` 并显式执行带 token 的 `releaseClaim`。禁止 `DiscardPolicy/DiscardOldestPolicy`。`CallerRunsPolicy` 虽可背压，但会让 scanner 重新承担 Confirm 等待，职责和指标不清晰，不作为首选。

参数关系：

- `batchSize <= 本轮可取得的 permit`，避免扫描 100 条后全部置为 SENDING 却只执行少量。
- `queueCapacity` 只吸收短暂调度抖动，不用于吞下长时间 RabbitMQ 故障；故障积压应留在数据库 `NEW/RETRY`。
- `scanDelay` 应从 1000 ms 逐步降到 200/100 ms 试验，但扫描空轮 QPS也要监控；有 backlog 时可立即续扫，无 backlog 时退避。
- `claim_until` 从 claim 成功时就开始计时，所以 `leaseSeconds` 必须覆盖 executor queue wait + Confirm timeout + DB 终态更新 + GC/调度裕量，初值保留 60 秒；不能只按 5 秒 Confirm timeout 设置。第一版不引入 lease heartbeat，先用 P2/P4/P8 的 `claim_to_worker_start_ms`、Confirm 尾延迟和 stale/expired claim 证明 60 秒是否安全。
- worker 增加会提高 Rabbit publish、Outbox 状态更新和下游消费到达率，但 worker 等 Confirm 时不应持有数据库连接。
- 最终容量受 Rabbit Confirm latency、Rabbit channel/connection、订单消费者并发、MySQL、CPU 和 GC共同限制。

优雅停机时先停止 scanner 接收新任务，再等待 executor 中任务完成；超过 shutdown timeout 的 SENDING 依靠租约恢复。停机等待上限必须小于或与 lease 设计协调，不能无限等待。

## 15. Publisher Confirm 处理

| 结果 | 语义 | 状态动作 |
|---|---|---|
| ACK 且无 Return | Broker 接收且消息可路由 | 统一 `PublishResult=ACK_ROUTED` 后，token CAS：`SENDING -> SENT` |
| ACK 但有 Return | 到达 exchange 但无法路由 | token CAS：`SENDING -> RETRY/DEAD` |
| NACK | Broker 未确认接受 | token CAS：`SENDING -> RETRY/DEAD` |
| timeout | 发布结果未知，消息可能已到达 | token CAS：`SENDING -> RETRY/DEAD`，允许后续重复 |
| send/future exception | 可能是发送前明确失败，也可能结果未知 | 保守按可重复投递处理，进入 RETRY/DEAD |
| 状态 CAS 失败 | 租约已变化或人工状态已变化 | 不覆盖新状态，记录 stale-worker 指标与日志 |

Publisher 必须只返回一个结构化的最终 `PublishResult`，任务层只能根据该结果执行一次终态 CAS，不能在收到 ACK 时先 `markSent`、再异步等待 Return。当前 Spring AMQP 3.2.10 的 `CorrelationData` 契约明确保证 returned message 在 Confirm future 完成前已写入；底层 `PublisherCallbackChannelImpl` 也在 Return 处理线程中先设置 `CorrelationData.returned`，并在 Confirm 处理时等待 Return callback 协调。因此同步等待 future 后再一次性判断 `confirm.isAck() && correlationData.getReturned() == null` 可以形成单一最终结果，不存在 ACK 先写 SENT、Return 后到再纠正的业务状态竞态。每次发布 attempt 的 CorrelationData id 使用 `messageId + claimToken` 保证唯一，消息属性中的业务 `messageId` 保持不变。

Confirm timeout 保持有界并配置化。失败日志应记录 `messageId`、`producerService`、`claimOwner`、`claimToken`、异常类型和 Confirm latency。不能把 timeout 解释为“Broker 一定没收到”。必须增加 `claim_to_worker_start_ms` Timer，用于验证排队时间与 60 秒 lease 的安全裕量。

## 16. Retry / Backoff

保留 `retry_count`、`next_retry_time` 和最大次数。当前退避为 5、30、120、300 秒，5 次失败进入 `DEAD`；改造后建议配置化，并采用带上限和 jitter 的指数退避，避免 RabbitMQ 恢复时所有实例同时重试：

```text
delay = min(maxDelay, baseDelay * 2^(retryCount-1)) + randomJitter
```

约束：

- ACK/NACK/Return/timeout/发布异常才增加发送 `retry_count`。
- executor 饱和导致的本地拒绝不应消耗发送重试次数；它是本机背压，不是 RabbitMQ 故障。拒绝后不能设置 `next_retry_time=now` 形成 scanner 热循环，应使用配置化的 `localBackpressureDelay`（建议 100～500 ms）加 jitter 后再开放 claim。
- 租约过期表示结果未知，第一版可保持现有行为：转 RETRY、立即可重试、不增加发送失败次数，同时单独累计 `expired_claim_total`。若反复租约过期形成活锁，再设计独立 `reclaim_count` 或进入人工审核，不能悄悄无限循环。
- 人工 retry/replay 重置次数时必须同时清空 owner、token、until、sentAt，并以期望状态 CAS，保持当前管理接口的幂等边界。

## 17. Worker 宕机恢复

1. worker claim 后行处于 `SENDING(token-A, until=T)`。
2. 进程在 publish 前、等待 Confirm 时或状态更新前宕机，数据库租约仍保留。
3. 其他线程/实例的 reclaimer 只回收 `status=SENDING AND claim_until <= DB_NOW` 的行，将其转为 `RETRY` 并清除 owner/token/until。
4. 新 scanner 以 token-B 再次 claim 和发布。
5. 如果旧 worker 只是长暂停后恢复，它使用 token-A 的 `markSent/markFailed` 会 CAS 失败，不能覆盖 token-B 的租约。

reclaim 可以保持独立低频任务，或由 scanner 周期触发，但必须按 `producer_service` 隔离。大量过期行应分批回收，避免一次 UPDATE 扫描/锁定过多记录。

## 18. 重复 Publish 语义

重复发布是 at-least-once 的正常结果，典型窗口包括：

- Broker ACK 后，生产者更新 `SENT` 前宕机。
- Confirm timeout，但 Broker 实际已经接收。
- Return/NACK/连接异常边界下结果不确定。
- 人工 replay 已发送消息。
- 消费业务事务提交后，RabbitMQ ACK 丢失导致重新投递。

重试必须复用 Outbox 原始 `messageId` 和业务键，不能每次生成新 ID。失败重试不会凭空保证“无重复订单”；无重复副作用由消费日志唯一约束、订单/requestId/deductNo 唯一约束、事务和条件状态转换共同保证。

## 19. Consumer 幂等关系

生产端并发化不改变消费语义：

- `fo_mq_consume_log.uk_message_consumer(message_id, consumer_group)` 是消息级重复投递防线。
- `OrderCreateMessageServiceImpl` 将消费日志、订单创建和结果 Outbox 放在同一事务；已成功消费的重复命令直接返回。
- 订单表的 `request_id`、`deduct_no`、`order_no` 唯一约束是业务级最终防线。
- `OrderResultMessageServiceImpl`、`OrderStateMessageServiceImpl` 也写消费日志，并使用预扣状态条件更新防止重复库存副作用和非法终态覆盖。
- 消费成功但 ACK 丢失时可安全重投；技术异常有限重试后进入 DLQ。

新增发布并发测试必须证明这些防线仍成立，而不能只断言 Outbox 最终都是 SENT。

## 20. Resource / Order 两侧改造范围

两侧应统一 **状态语义、claim contract、executor 行为、配置命名、指标名称和测试契约**，否则一侧修复 stale worker、另一侧仍可能覆盖租约。

第一阶段不建议立即把两个模块强行抽成依赖 Rabbit/MyBatis 的庞大公共基类。推荐：

1. 先定义相同的 `ClaimedOutbox`（含 entity + owner + token + until）和 service 方法契约。
2. 在两侧实现相同的 CAS SQL与 executor 配置。
3. 用共享测试清单验证行为一致。
4. 实现稳定后，再决定是否在公共模块提取不依赖领域实体的 coordinator/port；不能为了消除少量重复代码扩大本轮风险。

resource 侧主要发布 `ORDER_CREATE_COMMAND`；order 侧同时发布订单创建结果和订单状态事件。它们共享表但按 `producer_service` 分区，不能互相回收或更新对方的租约。

## 21. 数据库字段是否需要新增

`claim_until`、`retry_count`、`next_retry_time` 已存在并保留。推荐新增：

```sql
ALTER TABLE fo_mq_outbox
  ADD COLUMN claim_owner VARCHAR(128) DEFAULT NULL COMMENT '发布租约实例标识',
  ADD COLUMN claim_token VARCHAR(64) DEFAULT NULL COMMENT '单次发布租约fencing token';
```

`claim_owner` 用于定位和观测，`claim_token` 用于陈旧 worker fencing。若选择 `BIGINT claim_version` 递增实现 fencing，则不再同时增加 token；第一版更推荐 token，因为 worker 可以在 claim 前生成并直接携带。

索引建议先保留并核验执行计划：

- `idx_producer_status_retry(producer_service, status, next_retry_time)` 支持候选扫描。
- 当前 `idx_status_claim(status, claim_until)` 支持回收，但共享表下可评估改为/新增 `(producer_service, status, claim_until)`，避免两侧扫描彼此数据。

是否调整索引必须由 `EXPLAIN`、扫描行数和写入开销决定。不能只因字段增加就建立 owner/token 索引；正常路径不按 token 单独查询，而是在主键更新条件中校验。

## 22. 配置项

两侧采用同构配置前缀，例如：

```yaml
floworder:
  mq:
    outbox:
      scan-delay-ms: 200
      batch-size: 20
      lease-seconds: 60
      confirm-timeout-ms: 5000
      max-retry: 5
      retry-base-ms: 5000
      retry-max-ms: 300000
      retry-jitter-ms: 1000
      local-backpressure-delay-ms: 250
      local-backpressure-jitter-ms: 250
      publisher:
        workers: 2
        queue-capacity: 4
        max-in-flight: 6
        shutdown-await-seconds: 10
```

配置必须校验：workers、queue、batch、in-flight 均为正数；`max-in-flight <= workers + queue-capacity` 或采用同一容量定义；lease 显著大于 Confirm timeout；shutdown await 有界。不同服务可以有不同数值，但含义一致。

Hikari 本阶段不直接从 10 改到 50。发布 worker 等 Confirm 时不应持有数据库连接，但每条消息至少产生 claim 和最终状态更新，下游消费者也会增加事务速率。后续按 10（当前）→20→30 分档，仅在 Hikari pending、DB CPU/锁等待、事务耗时和连接使用证明确有需要时调整；每次只改变一个主要容量变量。

## 23. 修改文件清单（实施阶段）

以下是后续实施预计范围，本设计阶段不修改它们：

### resource-service

- `.../resource/entity/MqOutboxEntity.java`：增加 owner/token 映射。
- `.../resource/mapper/MqOutboxMapper.java`：增加明确的 claim、token CAS、分批回收 SQL（必要时 XML）。
- `.../resource/mq/service/MqOutboxService.java`
- `.../resource/mq/service/impl/MqOutboxServiceImpl.java`
- `.../resource/mq/task/MqOutboxPublishTask.java`：scanner/dispatch 化。
- `.../resource/mq/publisher/OutboxMessagePublisher.java`：返回结构化发布结果或以 token 更新。
- 新增 publisher executor/config properties/metrics 类。
- `application.yaml`：增加保守默认配置。
- 新增 Outbox service、task、publisher 并发与恢复测试。

### order-service

- `.../entity/MqOutboxEntity.java`
- `.../mapper/MqOutboxMapper.java`
- `.../mq/service/MqOutboxService.java`
- `.../mq/service/impl/MqOutboxServiceImpl.java`
- `.../mq/task/OrderResultOutboxPublishTask.java`
- `.../mq/publisher/OrderResultOutboxPublisher.java`
- 新增同构 executor/config properties/metrics 和测试。
- `application.yaml`：增加同构配置。

### SQL / 文档

- 新增可回滚、可重复检查的数据库迁移文件；同步更新 `sql/floworder.sql` 的基准结构。
- 后续新增 After 报告，不覆盖 `benchmark/reports/20260825-smoke-gate-report.md` 或原始数据。

管理重放逻辑也必须清除新 owner/token 字段；否则人工重放可能继承旧租约身份。

## 24. 风险

| 风险 | 后果 | 控制 |
|---|---|---|
| 旧 worker 覆盖新租约 | 错误 SENT/RETRY | claim token/fencing + 所有终态更新 CAS |
| executor 已拒绝但行已 SENDING | 假卡死至租约到期 | 拒绝时 token CAS 立即释放；指标告警 |
| RabbitMQ 故障时队列吸收无限积压 | JVM 内存/停机失控 | 小型有界队列，积压留在 DB |
| 并发增加压垮 CPU/DB | HTTP P95、Hikari pending恶化 | 2/4/8 阶梯、独立指标、闸门停止 |
| resource 快、order 慢 | 队列或 order Outbox 转移积压 | 分阶段测每一段 QPS与 backlog |
| lease 太短 | 正常 worker 被误回收、重复发布 | lease > timeout + 尾延迟 + GC裕量 |
| lease 太长 | 宕机恢复慢 | 记录恢复时间，结合 F4/F6 调整 |
| fixed-delay 降低导致空轮扫描 | DB 无效查询增多 | 无 backlog 退避、记录 scan/empty 指标 |
| 重试风暴 | Rabbit 恢复时瞬时饱和 | 指数退避 + jitter + in-flight 上限 |
| 优雅停机未排空 | 重复发布增加 | 先停 scanner、有限等待、租约恢复 |
| 两侧实现漂移 | 一侧仍存在正确性缺口 | 同一契约、同一测试矩阵、同批评审 |
| 把定位轮写成性能结论 | 简历证据失真 | 明确闸门失败与环境饱和边界 |

## 25. 自动化测试计划

### 25.1 Service / Mapper

1. N 个线程并发 claim 同一 id，只有一个成功并获得唯一 token。
2. 两个 producer service 不能 claim/reclaim 对方消息。
3. `next_retry_time` 未到不能 claim。
4. token-A 租约过期、token-B 重 claim 后，token-A 的 `markSent/markFailed/release` 全部失败且不改行。
5. ACK 路径只允许 `SENDING(token) -> SENT`。
6. NACK、Return、timeout、send exception 正确增加 retry、设置 backoff，达到上限进入 DEAD。
7. executor 拒绝转 RETRY但不增加发送 retryCount。
8. 回收仅处理已过期 SENDING；活动租约不变；回收清空 owner/token/until。
9. 人工 retry/replay 清理租约字段并保持期望状态 CAS。
10. 索引与查询使用 isolated MySQL 做集成测试，不用 H2 代替 MySQL 锁/更新语义。

### 25.2 Task / Executor

1. scanner 无 permit 时不 claim。
2. claim 失败释放 permit，不提交 worker。
3. worker 正常、异常、状态 CAS 失败都在 finally 释放 permit。
4. 队列满时无静默丢弃，无永久 SENDING。
5. batch 不超过 in-flight 上限。
6. scanner 同一方法不会因 scheduler pool 产生隐式重入假设。
7. 停机停止新扫描、等待在途任务；超时任务随后可被 lease 回收。

### 25.3 端到端正确性

1. 同一 `messageId` 重复投递只创建一个订单。
2. 订单结果重复/乱序不产生重复库存副作用或终态回退。
3. ACK 后、SENT 前模拟宕机，重发后最终收敛且重复订单/扣减为 0。
4. 双实例 claim 与 lease 接管。
5. RabbitMQ 停止/恢复后 backlog 最终归零。
6. DLQ、人工 replay、Instant `ACCEPTED` 继续使用原消息和状态语义。

## 26. Benchmark 验收计划

实施后不直接跑 500/1000 并发。

### Stage 0：实验隔离与基线保护

- 固定 After commit、JDK/中间件版本、MySQL库、Redis DB、Rabbit vhost、硬件和配置。
- 复制新的报告/原始结果目录，不修改 Before 资产。
- JMeter 尽量与服务分机；若仍同机，必须报告并把系统饱和轮判为无效容量结论。

### Stage 1：10 并发冒烟

验证请求成功、超卖 0、重复扣减 0、重复订单 0、库存守恒、Outbox/订单/请求最终收敛；并注入一次 NACK/Return/timeout 与租约回收最小路径。

### Stage 2：50 并发稳定负载

按 P2 → P4 → P8 逐档，每档至少预热后进行稳定到达率观察，不同时修改 Hikari。采集：

- HTTP admission QPS、P95、错误/业务结果。
- Outbox claim/publish/Confirm QPS与 Confirm latency。
- Order creation QPS、fulfillment QPS、P50/P95/P99/Max。
- resource/order 各自 backlog(t)，1～5 秒采样。
- RabbitMQ ready/unacked、publish/ack/consumer rate。
- CPU、memory、GC、线程、Hikari active/pending、DB CPU/锁等待/慢 SQL。
- duplicate order、duplicate deduction、负库存、库存恒等式。

通过条件：

1. 所有正确性不变量通过。
2. 稳定输入阶段 backlog 不持续线性增长；停止输入后在定义时间内归零。
3. fulfillment 延迟相较 Before 显著下降，且尾延迟无持续恶化。
4. CPU/JMeter/Hikari/Rabbit/消费者没有不可控饱和；无持续线程池拒绝。
5. admission 与 fulfillment 能形成可解释的稳定容量模型。

50 并发闸门通过后，才恢复 V1 → V2 → Instant、多轮阶梯、热点库存、MQ故障、状态竞争和 F1～F7。

### F1～F7 影响

- F1 Redis 准入后未持久化宕机：不改变，仍由 Instant orphan 扫描释放无 MySQL 事实的凭证。
- F2 Redis 成功/MySQL 明确失败：不改变，事务回滚且无幽灵 Outbox。
- F3 MySQL 已提交但响应未知：不改变，查询 MySQL 事实并继续 Outbox 履约，不能释放已接受库存。
- F4 RabbitMQ 停止：重点增强；积压留在 DB，恢复后受 in-flight、退避和 worker 限制有界排空。
- F5 消费成功但重新投递：重复概率仍存在，由消费日志和业务唯一约束兜底。
- F6 执行实例宕机：对 Outbox 新增 token fencing 的双实例 lease 接管验证；V8 请求 lease 仍是另一条独立租约链路。
- F7 Poison Message：生产并发不改变有限消费重试、DLQ持久化和人工闭环。

## 27. Before / After 指标定义

### 27.1 速率

- `HTTP admission QPS`：单位时间完成 Instant HTTP 响应的请求数，按 ACCEPTED/PROCESSING/REJECTED 分开。
- `Outbox claim QPS`：单位时间成功从 NEW/RETRY CAS 到 SENDING 的数量，resource/order 分开。
- `Outbox publish QPS`：单位时间调用 Rabbit send 的消息数，含重试；另报 unique message QPS。
- `RabbitMQ Confirm QPS`：单位时间完成 ACK/NACK/timeout/Return 判定的数量，按结果分组。
- `Order creation QPS`：单位时间新建唯一订单数，不用重复消费次数代替。
- `Fulfillment QPS`：单位时间从已受理请求收敛到订单创建结果的唯一请求数。

### 27.2 积压与延迟

```text
backlog(t) = count(status in NEW, RETRY, SENDING)
```

resource/order 分开采样，同时记录 NEW、RETRY、SENDING 子状态。稳定性不能只看测试结束时归零：持续输入窗口中，对 backlog(t) 做斜率估计；若长期正斜率，说明到达率大于服务率。停止输入后记录 drain time。

Fulfillment latency 从业务受理提交时间到订单结果收敛时间；当前字段为秒级时必须披露精度限制，若需要毫秒级比较应先改为 DATETIME(3) 或使用可靠事件时间，但这属于后续单独迁移。

### 27.3 对比表

| 指标 | Before | After P2 | After P4 | After P8 |
|---|---:|---:|---:|---:|
| HTTP QPS/P95 | 399.14 / 322 ms（饱和定位轮） | 待测 | 待测 | 待测 |
| Outbox publish QPS | 39～40/s | 待测 | 待测 | 待测 |
| Confirm P50/P95/P99 | 未完整采集 | 待测 | 待测 | 待测 |
| fulfillment QPS | 约 39～40/s | 待测 | 待测 | 待测 |
| fulfillment P50/P95/P99/Max | 129/221/229/232 s | 待测 | 待测 | 待测 |
| backlog 最大值/稳态斜率/drain time | 未完整采集 | 待测 | 待测 | 待测 |
| CPU avg/peak | 96.5%/100% | 待测 | 待测 | 待测 |
| Hikari active/pending peak | 10/46 | 待测 | 待测 | 待测 |
| 正确性不变量 | 通过 | 待测 | 待测 | 待测 |

After 不能只选择最好一轮；每档保留预热、至少 3 轮（正式结论优先 5 轮）原始结果并报告离散程度。

## 28. 实施步骤

1. 先补资源侧与订单侧现有 Outbox service 的回归测试，冻结 NEW/SENDING/SENT/RETRY/DEAD、人工重放和消费幂等语义。
2. 增加 owner + token 数据库迁移和组合回收索引的 EXPLAIN 证据；不修改历史 benchmark 数据。
3. 修改 claim/mark/reclaim/reset 为 token CAS，并先完成 stale-worker、双实例 claim、过期回收测试。
4. 新增专用有界 executor、permit 和带短暂 backpressure delay 的拒绝恢复；严格执行“先 reserve permit、再按 permit 查询、再 claim、再 submit”，保持 worker 内同步 Confirm。
5. resource-service 先接入 P2 配置并完成定向自动化测试；其间不改 Hikari。
6. order-service 按同一 contract 接入，并验证结果/状态两类 Outbox。
7. 加入 claim、publish、Confirm、backlog、executor、expired-claim 指标和结构化日志。
8. 完成编译、单元/集成、双实例和 RabbitMQ 恢复测试。
9. 在新的 After 目录执行 10 并发闸门。
10. 执行 50 并发 P2 → P4 → P8，逐档判断 CPU、Hikari、Rabbit、消费者和 backlog；任何闸门失败立即停止扩大。
11. 仅在证据显示连接池是独立瓶颈时，再做 Hikari 10 →20→30 单变量实验。
12. 若 P8 仍受 Confirm 等待限制且其他资源有余量，再单独评审方案 B；不在方案 A 中混入 callback 化。

## 建议实施顺序

```text
冻结正确性测试
  -> claim token/fencing 与租约 CAS
  -> 有界 executor + permit + 拒绝恢复
  -> resource/order 两侧同构接入
  -> 指标与优雅停机
  -> 自动化/双实例/RabbitMQ恢复验证
  -> 10并发闸门
  -> 50并发 P2/P4/P8
  -> 有证据后再决定 Hikari 或异步 Confirm
```

本轮推荐停在设计评审，不修改业务代码、不继续 Benchmark。评审通过后，应把“正确性 fencing”与“容量并发化”拆成可独立验证的小步提交，避免一次同时改变状态机、线程模型、连接池和压测参数。
