# FlowOrder 简历与面试最终稿

更新时间：2026-07-08

## 1. 简历项目名称

```text
FlowOrder：高并发预约交易与履约一致性平台
```

## 2. 技术栈

```text
Java 17、Spring Boot 3.x、Spring Cloud Alibaba、MyBatis Plus、
MySQL、Redis、RabbitMQ、Redisson、Nacos、Gateway、Sentinel、Actuator、JMeter
```

说明：简历里不要写 Java 21，不要写 Kafka、Seata、K8s、分库分表、多级缓存，当前项目没有这些已验证成果。

## 3. 项目描述

### 150 字版本

FlowOrder 是一个面向高并发预约交易场景的后端项目，围绕预约准入、异步受理、库存预扣、订单履约和异常恢复构建完整业务闭环。项目基于 Redis Lua、MySQL 条件更新和唯一索引保证并发下无超卖、无负库存和请求幂等；基于 Outbox + RabbitMQ 实现异步下单、可靠投递、消费幂等和死信兜底；引入持久化预约请求表、数据库租约和有界线程池实现 V8 异步预约处理；通过订单状态机和 MQ 事件驱动库存 SOLD/RELEASED，并补充 JMeter、SQL 核对和自动化测试证据。

### 口头 30 秒版本

这个项目不是普通商城 CRUD，我主要围绕预约交易里的三个问题做：第一，高并发下库存不能超卖；第二，库存预扣和订单创建跨服务后要最终一致；第三，异常消息和死信要能恢复。我的方案是 Redis Lua + MySQL 条件更新保证库存正确，Outbox + RabbitMQ 保证消息可靠，V8 用请求持久化、数据库租约和有界线程池做异步处理，订单确认、取消、超时通过状态事件驱动库存最终收敛。

## 4. 简历核心工作

可以直接选 4～5 条写进简历：

1. 设计预约准入与库存预扣链路，基于 Redis Lua 原子扣减、MySQL `available_stock >= quantity` 条件更新、`requestId` 唯一索引和参数一致性校验，保证并发下无超卖、无负库存和重复请求结果一致。

2. 基于 Outbox + RabbitMQ 构建异步下单链路，将业务数据和消息记录放在同一本地事务提交，结合 Publisher Confirm、发送租约、手动 ACK、消费幂等表和 DLQ，保证订单创建消息可追踪、可重试、可恢复。

3. 引入 V8 持久化异步预约引擎，请求先写入 `fo_reservation_request`，再由 `claim_owner/claim_until` 数据库租约抢占和有界线程池处理；JMeter 100 并发、900 请求下 HTTP 错误率 0%，库存恒等式成立，预约请求、订单和消息状态最终收敛。

4. 建立订单履约状态机，订单确认时将锁定库存转为成交库存，订单取消或超时时释放库存和用户额度，并通过订单状态事件回写预约请求履约状态；已完成确认、取消、超时三条链路的 SQL 核对。

5. 设计最小恢复控制面，对 DLQ 死信恢复采用 `preview -> execute` 两阶段操作，使用 `actionRequestId` 保证恢复动作幂等，并通过 `fo_recovery_action_log` 记录 operator、reason、预览结果和执行结果，避免直接裸调恢复接口。

## 5. 已有证据

| 能力 | 证据 |
| --- | --- |
| V8 异步受理压测 | JMeter 100 并发、900 请求、0% HTTP 错误率、吞吐 91.4/sec |
| 库存正确性 | 压测后库存恒等式 `total = available + locked + sold`，`diff=0` |
| 确认成交 | `ORDER_CONFIRMED` 后预扣记录 `SOLD`，`locked -> sold` |
| 取消释放 | `ORDER_CANCELLED` 后预扣记录 `RELEASED`，额度释放 |
| 超时关闭 | `ORDER_TIMEOUT` 后订单 `TIMEOUT`，预扣 `RELEASED`，额度释放 |
| V10 execute 幂等 | `RecoveryServiceImplTest` 验证第二次相同 `actionRequestId` 返回 `IDEMPOTENT_SUBMITTED`；业务收敛单独验证 |
| 架构说明 | `docs/architecture/floworder-architecture.md` |

证据文档：

- `docs/reports/v12/01-main-flow-evidence.md`
- `docs/reports/v12/02-v8-jmeter-evidence.md`
- `docs/reports/v12/03-recovery-evidence.md`
- `docs/reports/v12/04-timeout-close-evidence.md`

## 6. 三个技术难点

### 难点一：高并发下如何防止库存超卖

问题：多个请求同时预约同一个库存项，单纯先查库存再更新会出现并发超卖。

方案：

```text
Redis Lua 原子扣减：快速判断库存并扣减，减少无效请求进入 MySQL。
MySQL 条件更新：UPDATE 时带 available_stock >= quantity 条件，作为最终正确性防线。
唯一索引幂等：requestId / deductNo 唯一，防止重复扣减。
事务提交：库存字段、预扣记录、Outbox 在本地事务内提交。
```

面试重点：Redis 负责性能和快速失败，MySQL 条件更新才是最终防线。不能说“只靠 Redis 保证不超卖”。

### 难点二：库存预扣成功后如何保证订单消息不丢

问题：如果库存扣减成功后直接发 MQ，可能出现“数据库成功但 MQ 失败”或者“MQ 成功但数据库回滚”的不一致。

方案：

```text
Outbox：业务数据和消息记录同事务落库。
后台发送：定时任务扫描 NEW/RETRY 消息。
Publisher Confirm：Broker ACK 且可路由后才标记 SENT。
消费幂等：messageId + consumerGroup 唯一约束。
手动 ACK：业务处理成功后才 ACK。
DLQ：多次失败后进入死信，交给恢复控制面处理。
```

面试重点：这不是强一致分布式事务，而是通过本地事务 + 可靠消息 + 状态机实现最终一致。

### 难点三：为什么 V8 要先持久化请求，而不是直接开线程处理

问题：如果 HTTP 请求直接丢给线程池，服务重启或队列满时请求可能丢失，且多实例下无法协调处理权。

方案：

```text
请求先写 fo_reservation_request。
后台任务扫描 PENDING/RETRY。
用 claim_owner 和 claim_until 做数据库租约抢占。
线程池和队列有界，过载时请求留在数据库，后续重试。
库存扣减、额度更新和 Outbox 写入仍在同一事务中完成。
```

面试重点：JVM 队列只负责单机调度，分布式正确性靠数据库租约、唯一索引、条件更新和状态机。

## 7. 高频面试问答

### Q1：你怎么保证不超卖？

我用了 Redis Lua + MySQL 条件更新双重控制。Redis Lua 原子检查并扣减库存，先把明显库存不足的请求挡掉；MySQL 更新库存时带 `available_stock >= quantity` 条件，保证即使 Redis 层出现异常，数据库也不会把库存扣成负数。同时用 `requestId` 和预扣单唯一索引保证重复请求不会重复扣减。压测后我用 SQL 校验库存恒等式，`diff=0`。

### Q2：Redis Lua 成功但 MySQL 失败怎么办？

如果 MySQL 明确失败，业务会做 Redis 补偿；如果数据库提交结果未知，不盲目加回 Redis，而是优先删除库存 key，后续从 MySQL 重建，避免因为未知提交状态导致 Redis 库存被错误放大。

### Q3：为什么不用 Seata？

这个场景不要求库存预扣和订单创建强一致提交，最终一致更合适。Seata AT 有全局锁和性能成本，TCC 对业务侵入大。FlowOrder 用 Outbox、幂等消费、条件状态转换和补偿闭环实现最终一致，复杂度更可控，也更贴合高并发预约交易。

### Q4：Outbox 和直接发 MQ 有什么区别？

直接发 MQ 会有双写问题：数据库成功但 MQ 失败，或者 MQ 发出后数据库回滚。Outbox 把“要发送的消息”作为一条表记录和业务数据放在同一个本地事务提交。后续由任务异步发送，发送失败可重试，发送成功后再标记 `SENT`。

### Q5：MQ 重复投递怎么办？

消费端用 `messageId + consumerGroup` 做唯一约束。消费者先写消费日志，再执行业务状态转换，状态转换本身也带原状态条件。重复消息到达时，如果消费日志已经成功，就直接 ACK，不重复改业务数据。

### Q6：订单状态事件乱序怎么办？

resource-service 的订单状态消费者允许状态消息早于订单创建结果到达，所以 `ORDER_CONFIRMED` 可以把 `PRE_DEDUCTED/ORDER_CREATED` 转为 `SOLD`，取消和超时可以转为 `RELEASED`。迟到的创建成功结果不能覆盖 `SOLD/RELEASED` 终态。

### Q7：V8 为什么要数据库租约？

V8 请求先落库，多个实例都可能扫描到同一条请求。数据库租约通过 `claim_owner/claim_until` 条件更新抢占处理权，只有抢占成功的实例可以处理。实例崩溃后租约过期，其他实例可以重新抢占，避免请求永久卡住。

### Q8：线程池在项目里解决什么问题？

线程池不是用来保证分布式正确性的，它只解决单实例内的处理能力隔离和过载保护。V8 的正确性仍由请求表、租约、唯一索引、Redis Lua、MySQL 条件更新和状态机保证。线程池必须有有界队列、拒绝策略和关闭策略，不能随便用无界 `@Async`。

### Q9：死信怎么恢复？

死信先落库到 `fo_mq_dead_letter`，恢复时不直接裸调重放，而是走 V10 recovery：先 `preview` 判断当前状态、影响范围和风险，再 `execute` 执行恢复动作。`execute` 必须携带 `actionRequestId`，同一个动作重复提交会幂等返回，并写 `fo_recovery_action_log` 作为审计记录。

### Q10：项目现在最大的不足是什么？

我不会把它说成生产级高可用系统。当前主要是本地环境下证明并发正确性、可靠消息和最终一致性。还缺真实多实例部署、完整监控告警、更高容量压测和系统化故障演练。后续如果继续做，会优先补 Sentinel/RabbitMQ/Redis 故障证据和更高并发压测，而不是盲目加新业务。

## 8. 风险点应答

| 面试追问 | 建议回答 |
| --- | --- |
| “91 QPS 也叫高并发？” | 这是本地单机正确性基线，重点验证无超卖、无负库存和状态收敛。高 QPS 可以继续优化，但不能牺牲正确性。 |
| “为什么没有分库分表？” | 当前项目重点是并发正确性和最终一致性，不是海量数据治理。分库分表需要先有数据规模和查询瓶颈证据。 |
| “为什么没有多级缓存？” | 库存是强一致敏感写链路，本地缓存会增加一致性复杂度。当前瓶颈不在热点读缓存，所以没有引入 Caffeine。 |
| “为什么没有完整后台？” | recovery-service 是最小恢复控制面，只解决异常恢复审计问题，不扩展成通用运营后台，避免项目失焦。 |
| “是不是生产级？” | 不是。它是面向生产问题设计并在本地完成验证的学习/简历项目，生产级还需要真实部署、监控、告警和故障切换证据。 |

## 9. 不要写进简历

```text
生产级高可用系统
完整运营后台
多级缓存平台
分库分表
Kubernetes 部署
Seata 分布式事务
Kafka 双 MQ 架构
完整 Prometheus/Grafana 监控平台
```

这些没有当前代码和证据支撑，可以作为后续优化方向，不能包装成已完成成果。

## 10. 推荐简历版本

如果简历空间有限，最终建议写成下面这种：

```text
FlowOrder：高并发预约交易与履约一致性平台

围绕预约准入、异步受理、库存预扣、订单履约和异常恢复，设计并实现预约交易一致性闭环。
- 基于 Redis Lua + MySQL 条件更新 + 唯一索引实现库存预扣，保证并发下无超卖、无负库存和 requestId 幂等。
- 基于 Outbox + RabbitMQ 构建异步下单链路，结合 Publisher Confirm、手动 ACK、消费幂等表和 DLQ 实现消息可追踪、可重试、可恢复。
- 引入持久化预约请求表、claim_owner/claim_until 数据库租约和有界线程池实现 V8 异步预约处理；JMeter 100 并发、900 请求下 HTTP 错误率 0%，库存恒等式 diff=0。
- 建立订单履约状态机，确认订单时 locked -> sold，取消/超时时 locked -> available，并通过 MQ 状态事件回写预约请求履约状态。
- 设计 preview -> execute 恢复控制面，使用 actionRequestId 保证恢复动作幂等，并通过恢复审计日志记录 operator、reason 和执行结果。
```
