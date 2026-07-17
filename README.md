# FlowOrder

FlowOrder 是一个面向 Java 后端简历和面试的高并发预约交易与履约一致性平台。

项目围绕“预约准入 -> 异步受理 -> 库存预扣 -> 可靠下单 -> 订单履约 -> 异常恢复”构建闭环，重点证明并发正确性、可靠消息、最终一致性、线程池治理、压测验证和故障恢复能力。

> 当前状态：主体功能已收口，V7-lite / V8 / V9-lite / V10-core / V12 证据链已完成。后续不再扩普通业务模块，只做 bug 修复、事实修正和面试复盘。

## 项目定位

FlowOrder 不是商城、运营后台、支付系统、Agent 项目或云原生平台。

它聚焦一个高并发预约交易场景：

```text
用户提交预约
-> 系统校验资源状态、预约窗口和用户额度
-> 请求先持久化并异步处理
-> Redis Lua + MySQL 条件更新完成库存预扣
-> Outbox + RabbitMQ 异步创建订单
-> 订单确认/取消/超时后驱动库存 SOLD/RELEASED
-> 死信和异常通过恢复控制面 preview/execute 处理
```

核心目标不是堆接口数量，而是证明：

- 高并发下不超卖、不负库存；
- 相同 `requestId` 幂等且参数一致；
- 跨服务订单创建最终一致；
- MQ 消息可追踪、可重试、可恢复；
- 订单确认、取消、超时均能驱动库存收敛；
- 恢复动作可预览、可幂等、可审计；
- 关键结论有 JMeter、SQL、日志和自动化测试证据。

## 技术栈

以当前 `pom.xml` 和配置为准：

- Java 17
- Spring Boot 3.5.14
- Spring Cloud 2025.0.0
- Spring Cloud Alibaba 2025.0.0.0
- MyBatis Plus 3.5.15
- MySQL
- Redis
- RabbitMQ
- Redisson 3.32.0
- Nacos
- Gateway
- Sentinel
- Actuator
- JMeter

当前没有实现或验证：Kafka、Seata、分库分表、Kubernetes、完整 Prometheus/Grafana 监控平台、多级缓存平台。

## 模块说明

| 模块 | 说明 |
| --- | --- |
| `floworder-common` | 公共响应、异常、枚举、共享 DTO |
| `floworder-server-client` | Feign 契约、跨服务 DTO、MQ 协议 |
| `floworder-resource-service` | 预约入口、V7 准入、V8 异步请求、库存预扣、订单结果/状态消费、DLQ 与恢复控制面 |
| `floworder-order-service` | 订单创建、订单查询、确认、取消、超时关闭、订单状态日志、订单侧 Outbox |
| `floworder-gateway-service` | 统一入口、网关路由和基础限流 |
| `floworder-service-initialize` | 组合校验基础设施 |
| `floworder-redisson-framework` | Redisson 公共配置 |
| `sql` | 表结构和基础数据 |
| `jmeter` | 压测脚本与实验材料 |
| `apifox` | OpenAPI / Apifox 接口集合 |
| `docs` | 架构图、验证报告、简历与面试材料 |

## 当前核心链路

### 1. V7-lite：预约准入

实现最小但必要的预约准入：

- 资源/库存项状态校验；
- 预约窗口校验；
- 用户资格和额度校验；
- 并发限购；
- 无效请求前置拦截。

正确性边界：额度和资源状态的事实来源是 MySQL，不能只依赖 Redis 或应用层先查后写。

### 2. V8：持久化异步预约引擎

V8 入口：

```text
POST /api/reservation/create/v8
```

核心设计：

- 请求先写入 `fo_reservation_request`；
- HTTP 快速返回 `requestId`；
- 后台任务扫描待处理请求；
- 使用 `claim_owner` / `claim_until` 做数据库租约抢占；
- 使用有界线程池和显式队列处理请求；
- 库存扣减、额度更新、预扣记录和 Outbox 写入仍在同一个事务边界内完成。

JVM 队列只负责单实例调度；分布式正确性继续由数据库租约、唯一索引、Redis Lua、MySQL 条件更新和状态机保证。

### 3. 库存预扣：Redis Lua + MySQL 条件更新

库存不变量：

```text
total_stock = available_stock + locked_stock + sold_stock
```

核心机制：

- Redis Lua 原子检查并扣减库存，快速失败；
- MySQL 条件更新使用 `available_stock >= quantity` 作为最终防线；
- `requestId` / `deductNo` 唯一索引防止重复扣减；
- 明确业务失败时做补偿；
- 数据库提交结果未知时避免盲目增加 Redis 库存，优先删除 key 后从 MySQL 重建。

### 4. V3/V8 主链路：Outbox + RabbitMQ 可靠下单

核心机制：

- 业务数据和 Outbox 记录在同一本地事务提交；
- 后台任务扫描 `NEW/RETRY` Outbox；
- Publisher Confirm 成功后标记 `SENT`；
- 发送失败退避重试，超过上限进入 `DEAD`；
- 消费端使用 `messageId + consumerGroup` 幂等；
- 消费成功后手动 ACK；
- 多次失败进入 RabbitMQ DLQ，并落库到 `fo_mq_dead_letter`。

### 5. V9-lite：订单履约状态机

订单主状态：

| 状态 | 语义 |
| --- | --- |
| `10 RESERVED` | 已预约 |
| `20 CONFIRMED` | 已确认 |
| `30 CANCELLED` | 已取消 |
| `40 TIMEOUT` | 已超时 |
| `50 FAILED` | 创建失败 |

状态事件驱动库存变化：

| 订单事件 | 库存预扣状态 | 库存字段变化 |
| --- | --- | --- |
| `ORDER_CONFIRMED` | `SOLD` | `locked_stock -> sold_stock` |
| `ORDER_CANCELLED` | `RELEASED` | `locked_stock -> available_stock` |
| `ORDER_TIMEOUT` | `RELEASED` | `locked_stock -> available_stock` |

确认、取消、超时三条链路均已形成 SQL 和 MQ 证据。

### 6. V10-core：恢复控制面

恢复接口默认不暴露：

```yaml
floworder:
  admin:
    enabled: false
```

临时开启后支持：

```text
POST /internal/recovery/dead-letter/preview
POST /internal/recovery/dead-letter/execute
GET  /internal/recovery/reservation/check?requestId=xxx
```

设计原则：

- `preview` 只判断可执行性和影响范围，不直接修改业务；
- `execute` 必须携带 `actionRequestId`；
- 相同 `actionRequestId` 已提交后再次执行返回 `IDEMPOTENT_SUBMITTED`；
- `SUBMITTED` 只表示恢复命令已经可靠提交，业务是否收敛需要独立回查；
- 恢复动作写入 `fo_recovery_action_log`，记录 operator、reason、previewResult、executeResult；
- recovery 不绕过领域服务直接修改订单和库存核心状态。

## 已验证证据

### V8 JMeter 压测

| 指标 | 结果 |
| --- | ---: |
| Samples | 900 |
| Threads | 100 |
| Loop | 9 |
| Average | 16 ms |
| Max | 213 ms |
| Error % | 0.00% |
| Throughput | 91.4/sec |

SQL 核对结论：

```text
fo_reservation_request：900 条成功
fo_reservation_order：900 条 RESERVED
fo_stock_deduct_record：900 条 ORDER_CREATED
fo_mq_outbox：创建命令和结果消息均 SENT
库存恒等式 diff=0
```

### V12 主链路证据

| 场景 | 证据 |
| --- | --- |
| 确认成交 | `fo_reservation_order.status=20`，预扣记录 `SOLD`，库存 `locked -> sold`，`diff=0` |
| 取消释放 | `fo_reservation_order.status=30`，预扣记录 `RELEASED`，库存 `locked -> available`，额度释放 |
| 超时关闭 | `fo_reservation_order.status=40`，`ORDER_TIMEOUT` Outbox `SENT`，消费成功，库存和额度释放 |
| V10 execute 幂等 | `RecoveryServiceImplTest` 验证第二次相同 `actionRequestId` 返回 `IDEMPOTENT_SUBMITTED` |

## 文档入口

建议按以下顺序阅读：

1. 架构和流程图  
   [docs/architecture/floworder-architecture.md](docs/architecture/floworder-architecture.md)

2. V12 主链路证据  
   [docs/reports/v12/01-main-flow-evidence.md](docs/reports/v12/01-main-flow-evidence.md)

3. V8 压测证据  
   [docs/reports/v12/02-v8-jmeter-evidence.md](docs/reports/v12/02-v8-jmeter-evidence.md)

4. V10 恢复控制面证据  
   [docs/reports/v12/03-recovery-evidence.md](docs/reports/v12/03-recovery-evidence.md)

5. 订单超时关闭证据  
   [docs/reports/v12/04-timeout-close-evidence.md](docs/reports/v12/04-timeout-close-evidence.md)

6. 简历与面试最终稿  
   [docs/resume/floworder-interview-guide.md](docs/resume/floworder-interview-guide.md)

7. Apifox / OpenAPI 接口集合  
   [apifox/floworder.openapi.json](apifox/floworder.openapi.json)

早期 V8 实验过程记录仍保留在 `docs/reports/v8`，其中包含中间态问题和排查过程。判断最终项目状态时，以 `docs/reports/v12`、`docs/architecture`、`docs/resume` 和当前代码为准。

## 本地端口

| 服务 | 默认端口 |
| --- | ---: |
| gateway-service | 8088 |
| resource-service | 8081 |
| order-service | 8082 |
| Nacos | 8848 |
| RabbitMQ | 5672 |

主业务接口优先通过 Gateway：

```text
POST http://127.0.0.1:8088/api/reservation/create/v8
GET  http://127.0.0.1:8088/api/reservation/request/{requestId}
GET  http://127.0.0.1:8088/api/order/query?requestId=xxx
POST http://127.0.0.1:8088/api/order/confirm
POST http://127.0.0.1:8088/api/order/cancel
```

恢复控制接口为内部接口，默认关闭，必要时临时开启 `floworder.admin.enabled=true` 后直连 resource-service。

## 简历表达建议

可写内容：

```text
FlowOrder：高并发预约交易与履约一致性平台

围绕预约准入、异步受理、库存预扣、订单履约和异常恢复，设计并实现预约交易一致性闭环。
- 基于 Redis Lua + MySQL 条件更新 + 唯一索引实现库存预扣，保证并发下无超卖、无负库存和 requestId 幂等。
- 基于 Outbox + RabbitMQ 构建异步下单链路，结合 Publisher Confirm、手动 ACK、消费幂等表和 DLQ 实现消息可追踪、可重试、可恢复。
- 引入持久化预约请求表、claim_owner/claim_until 数据库租约和有界线程池实现 V8 异步预约处理；JMeter 100 并发、900 请求下 HTTP 错误率 0%，库存恒等式 diff=0。
- 建立订单履约状态机，确认订单时 locked -> sold，取消/超时时 locked -> available，并通过 MQ 状态事件回写预约请求履约状态。
- 设计 preview -> execute 恢复控制面，使用 actionRequestId 保证恢复动作幂等，并通过恢复审计日志记录 operator、reason 和执行结果。
```

不要写成：

- 生产级高可用系统；
- 完整运营后台；
- 完整权限系统；
- 多级缓存平台；
- 分库分表系统；
- Kubernetes 云原生部署；
- Seata 分布式事务；
- Kafka 双 MQ 架构；
- 完整 Prometheus/Grafana 监控平台。

这些能力没有当前代码和证据支撑，只能作为后续优化方向。

## 当前结论

FlowOrder 已完成主线收口：

```text
V7-lite 预约准入
-> V8 持久化异步预约
-> Redis/MySQL 库存预扣
-> Outbox + RabbitMQ 下单
-> V9 订单确认/取消/超时履约
-> V10 恢复控制面
-> V12 证据链、架构图和面试材料
```

后续建议停止主体功能开发，将时间投入到：

- 项目讲解训练；
- Java 并发、MySQL、Redis、RabbitMQ、Spring 事务复习；
- 算法题；
- 简历投递和模拟面试。
