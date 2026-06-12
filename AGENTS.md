# FlowOrder 项目协作指南

## 1. 文档作用范围

本文件适用于整个 `floworder` 仓库。

任何进入本项目的编码 Agent，在分析问题、提出方案或修改代码前，都应先阅读本文件。判断项目状态时，以当前代码、数据库脚本和实际测试结果为准，不能把聊天中讨论过的方案当作已经实现。

最后一次与仓库同步日期：2026-06-11。

## 2. 长期目标

用户正在准备杭州中大厂的 Java 后端与 AI Agent 工程方向实习。最终简历计划保留两个互相关联的主项目：

1. `FlowOrder`：高并发预约、订单、库存和任务履约平台。
2. `AgentDesk`：企业知识库与智能工单 Agent 平台。

后续 `AgentDesk` 将通过 Tool Calling 或 MCP 调用 FlowOrder 的业务接口，例如：

- 查询订单。
- 查询可用库存。
- 创建工单。
- 经人工确认后取消预约。

因此，FlowOrder 不能只是孤立的高并发演示项目，还需要逐步提供结构清晰、支持幂等、具备权限边界的业务 API。

## 3. 协作规则

用户希望通过亲自编写代码学习，而不是让 Agent 直接搬运或代写整个项目。

- 默认采用“指导用户实现”的方式：说明看哪些代码、为什么这样设计、具体如何实现、如何验证。
- 只有用户明确要求 Agent 修改或实现时，才能直接编辑项目代码。
- 给出的代码必须匹配仓库当前真实的包名、实体类、状态值、接口和框架版本。
- 回答实现问题前，先检查当前代码，不能依赖过期的聊天上下文猜测。
- 必须明确区分：
  - 当前已经存在的代码；
  - 当前仍然存在的缺陷；
  - 尚未实现的设计方案；
  - 后续版本的优化目标。
- 不复制完整参考项目。一次只吸收一个值得学习的工程思想，并根据 FlowOrder 的业务进行简化。
- 不为了堆技术栈增加复杂度。每个组件都必须解决明确的并发、一致性、可靠性或可观测性问题。
- 当前工作区可能存在用户尚未提交的修改，不得回滚与当前任务无关的变更。

## 4. 项目定位

FlowOrder 不是大型 CRUD 项目。项目的主要价值是围绕一个小而完整的业务场景，建立可解释、可验证的工程链路：

```text
请求校验
  -> Redis 库存预扣
  -> MySQL 库存预扣
  -> 远程幂等创建订单
  -> 确认预扣或执行补偿
  -> 超时关闭订单
  -> 释放库存或完成履约
  -> 输出可追踪的日志和指标
```

项目需要重点体现以下面试能力：

- Java 并发和锁粒度设计。
- Redis 原子库存扣减和缓存一致性。
- MySQL 条件更新、事务、索引和锁。
- 请求幂等与消息幂等。
- 远程调用结果不确定性和最终一致性。
- RabbitMQ 可靠投递、延迟处理、重试与死信队列。
- Sentinel 限流、熔断与降级。
- JMeter 性能对比和瓶颈分析。
- TraceId、监控指标和线上问题排查。

## 5. 当前技术栈事实

必须以根目录 `pom.xml` 中的真实配置为准，不能直接沿用早期规划：

- Java：17。
- Spring Boot：3.5.14。
- Spring Cloud：2025.0.0。
- Spring Cloud Alibaba：2023.0.3.3。
- Redisson：3.32.0。
- MyBatis Plus：3.5.15。
- MySQL 数据库：`floworder`。
- Redis database：1。
- Nacos：`127.0.0.1:8848`。
- resource-service：8081。
- order-service：8082。

RabbitMQ、Sentinel、Gateway、可观测性和部署相关内容属于后续路线。除非当前代码能够证明某项已经完成，否则不能在文档或简历中描述为已实现。

## 6. 仓库结构

主要模块如下：

- `floworder-common`
  - 通用响应、异常、枚举和公共工具。
- `floworder-server-client/floworder-order-client`
  - order-service 的 Feign 接口和 DTO。
- `floworder-server-client/floworder-resource-client`
  - resource-service 对外共享的客户端接口和 DTO。
- `floworder-server/floworder-resource-service`
  - 预约入口、组合校验链、Redis 库存、MySQL 预扣、结果补偿和版本策略。
- `floworder-server/floworder-order-service`
  - 幂等创建订单和订单查询。
- `floworder-server/floworder-gateway-service`
  - Gateway 模块。不能默认 README 中规划的所有网关能力都已完成。
- `floworder-spring-cloud-framework/floworder-service-initialize`
  - 组合校验树基础设施。
- `floworder-redisson-framework`
  - Redisson 公共配置。
- `sql/floworder.sql`
  - 当前数据库结构。
- `jmeter`
  - 版本对比和并发正确性测试计划。

## 7. 参考项目

本仓库之外的参考项目包括：

- `D:\JDK\IDEA\java_reinforcement_learning\damai_pro`
- `D:\JDK\IDEA\java_reinforcement_learning\dock-data-center`
- `D:\JDK\IDEA\java_reinforcement_learning\link-flow`
- GitHub 项目 `java-up-up/super-agent`

当前 FlowOrder 阶段主要从 `damai_pro` 学习：

- 组合校验树。
- 多版本订单策略。
- 不同版本之间的锁设计演进。
- 延迟取消订单。
- 消息消费幂等思想。

不应直接迁移：

- 大麦特有的节目、票档、座位和营销字段。
- FlowOrder 只有单个 `stockItemId` 时没有必要使用的多票档加锁逻辑。
- 不能解决当前实际问题的复杂框架封装。

## 8. 领域模型与不变量

当前核心表：

- `fo_resource`
- `fo_stock_item`
- `fo_reservation_order`
- `fo_stock_deduct_record`
- `fo_mq_message_log`
- `fo_order_status_log`

库存必须始终满足：

```text
total_stock = available_stock + locked_stock + sold_stock
```

任何库存字段都不能小于零。

预约订单状态：

- `0`：初始化。
- `10`：已预约。
- `20`：已确认。
- `30`：已取消。
- `40`：已超时。
- `50`：失败。

库存预扣记录状态：

- `10`：已预扣。
- `20`：已确认。
- `30`：已正常释放。
- `40`：失败。

后续应逐步将状态数字收敛为有明确含义的枚举或常量。新代码不能继续散落没有解释的状态数字。

请求幂等约束：

- `requestId` 表示一次逻辑预约请求。
- 相同 `requestId` 不能对应不同的用户、资源、库存项或数量。
- 数据库唯一索引是并发下的最终防线，但不能代替应用层清晰的幂等处理。

## 9. 组合校验链

组合校验树是 FlowOrder 有意保留的学习点。

当前校验职责包括：

- 请求参数校验。
- 资源是否存在、是否启用。
- 库存项是否存在、是否属于当前资源、是否启用。

业务校验与库存扣减必须分离。校验节点不能偷偷修改库存或创建订单。

校验树应保持在面试中能够清楚解释的复杂度，不需要复刻 `damai_pro` 的完整校验体系。

## 10. V1 当前设计

接口：

```text
POST /reservation/create/v1
```

当前 V1 主流程：

1. 根据 `stockItemId` 获取 Redisson 分布式锁。
2. 根据 `requestId` 查询已有库存预扣记录。
3. 生成 `orderNo` 和 `deductNo`。
4. Redis 不存在库存时，从 MySQL 初始化缓存。
5. 扣减 Redis 库存。
6. 在一个 MySQL 事务中插入 `PRE_DEDUCTED` 记录，并将库存从 `available_stock` 转移到 `locked_stock`。
7. 通过 Feign 调用 order-service 创建订单。
8. 订单创建成功后确认库存预扣记录。
9. 远程调用结果不确定时，通过查询 order-service 进行结果确认。
10. 定时扫描过期的 `PRE_DEDUCTED` 记录进行补偿。

V1 故意使用较大范围的分布式锁作为正确性基线。当前锁覆盖 Redis、MySQL 和远程 Feign 调用，因此同一个 `stockItemId` 的请求会串行执行，吞吐量受到明显限制。

## 11. V1 必须收口的工作

以下事项全部实现并通过测试之前，不能将 V1 描述为已经完成。

### 11.1 修正正常释放状态

截至 2026-06-11，`StockDeductServiceImpl.release()` 仍然把正常释放的预扣记录修改成状态 `40`。

正确语义应该是：

```text
正常成功释放 -> 30
无法正常处理的最终失败 -> 40
```

`ResourceOrderServiceImpl.handleOldDeductRecord()` 也必须明确处理状态 `30`。

### 11.2 正确表达远程调用结果未知

当前 `OrderClientFallback.create()` 仍然返回普通错误响应。这会导致 resource-service 把 order-service 不可用错误地判断为“明确创建失败”。

必须区分：

- order-service 可以访问，并明确返回业务拒绝：属于明确失败，可以释放库存。
- 超时、连接断开、fallback 或返回空响应：属于结果未知，保持 `PRE_DEDUCTED` 并进入结果确认。

### 11.3 增加有边界的补偿重试

当前库存预扣表还没有补偿所需的重试字段。

现有 SQL 中的：

```text
retry_count
next_retry_time
last_error
```

属于 `fo_mq_message_log`，不是 `fo_stock_deduct_record`。不能误认为预扣补偿已经具备这些字段。

实现补偿重试时，应给库存预扣记录增加：

```text
retry_count
next_retry_time
last_error
```

并建立以以下字段开头的索引：

```text
(status, next_retry_time)
```

V1 建议采用以下学习用退避策略：

```text
第一次确认：5 秒
第二次确认：10 秒
第三次确认：30 秒
```

处理规则：

- 查询到订单：确认状态 `10 -> 20`。
- order-service 不可用：保持状态 `10`，推迟到下一次确认。
- 第一次查询不到订单：不能立即释放库存。
- 多次查询都不存在，并且已经超过最终确认窗口：释放库存，状态 `10 -> 30`。

状态修改必须带原状态条件，确保多个任务实例并发处理时只有一个实例能够成功转换状态。

### 11.4 分离两种超时时间

以下两个时间不能继续混用：

- 结果确认时间：远程调用结果未知后，何时开始查询，初始可以是 5 秒。
- 订单过期时间：订单创建成功但用户长时间没有确认时，何时关闭，目前计划为 15 分钟。

库存预扣补偿定时任务不等于订单超时关闭任务。

### 11.5 完善诊断日志

捕获远程调用异常时，日志必须输出异常对象。否则连接拒绝、超时、404、fallback 和反序列化错误最终都会显示成同一句固定提示。

正式实现中，不要每 5 秒用 INFO 打印一次“扫描到 0 条记录”。空扫描应使用 DEBUG，或者只输出有意义的聚合指标。

## 12. 补偿语义

补偿不等于重新创建缺失订单。

V1 的结果确认任务主要采用反向补偿：

```text
订单存在
  -> 确认库存预扣

结果仍然未知
  -> 保持 PRE_DEDUCTED 并继续重试

多次确认后确定订单不存在
  -> 释放 MySQL 锁定库存
  -> 恢复或删除 Redis 库存缓存
  -> 将预扣记录改为 RELEASED
```

除非业务明确要求正向重试，否则不能在后台自动重新创建订单。客户端可能已经收到失败结果并放弃本次操作，后台晚些时候重新下单会改变用户预期。

Redis 库存恢复顺序：

1. 在本地事务中释放 MySQL 库存。
2. MySQL 成功后恢复 Redis。
3. 如果 MySQL 已成功而 Redis 恢复失败，删除 Redis 库存 key，让后续请求从 MySQL 重建。

补偿异常不能覆盖原始业务异常。应将补偿异常作为 suppressed exception 附加到原异常，并记录完整上下文。

## 13. V1 测试门槛

进入 V2 前，必须保留一份可对比的 V1 基线报告。

JMeter 必测场景：

1. 单个成功请求。
2. 相同 `requestId` 顺序重复请求。
3. 相同 `requestId` 并发请求。
4. 库存充足、每次使用唯一 `requestId`。
5. 库存 100、并发发送 1000 个唯一请求。
6. `quantity > 1` 的批量预约。
7. 流量分布到两个不同的 `stockItemId`。
8. 逐渐增加并发，观察锁竞争。
9. 请求前关闭 order-service。
10. order-service 已创建订单，但响应或结果确认失败。
11. 补偿任务查询到订单存在。
12. 补偿任务经过多次确认后确定订单不存在。
13. 持续 10 分钟的稳定性测试。

每次测试后都要核对数据库和 Redis，不能只看 HTTP 返回值。

必须满足：

```text
成功订单数 <= 初始可用库存
同一个 requestId 只能有一个订单
同一个 requestId 只能有一条预扣记录
库存不能为负
Redis 库存与预期的 MySQL available_stock 一致
total_stock = available_stock + locked_stock + sold_stock
```

需要记录：

- Throughput。
- Average。
- P90、P95、P99。
- Max。
- HTTP 错误率和业务错误率。
- 获取锁超时数量。
- 库存不足数量。

正式压测时关闭 `View Results Tree`，避免 JMeter GUI 监听器影响测试结果。

## 14. V2 目标

V2 是锁粒度和性能演进，不是重新开发一套无关业务。

新增接口：

```text
POST /reservation/create/v2
```

必须保留 V1，使用相同的 JMeter 数据和环境比较两个版本。

V2 设计：

1. 复用现有组合校验链。
2. 复用 `requestId` 幂等机制和数据库唯一索引。
3. 去掉包围整个下单流程的 Redisson 大锁。
4. 使用 Redis Lua 原子完成库存判断和扣减。
5. 保留 MySQL 条件更新作为持久层一致性防线。
6. 远程 Feign 调用期间不能持有分布式锁。
7. 复用已经修正的确认和补偿机制。
8. 在相同库存、并发量、机器和 JMeter 脚本下对比 V1 与 V2。

Lua 脚本必须原子完成：

```text
读取库存
判断库存 >= quantity
扣减库存
返回清晰的结果码
```

不要直接复制大麦 V2 的“本地锁 + 多把分布式锁”设计。FlowOrder 当前一次请求只涉及一个库存项，没有真实的多库存项加锁需求。

V2 验收标准：

- 正确性不能低于 V1。
- 相同 `requestId` 并发请求仍然幂等。
- 不能超卖。
- 吞吐量有明确提升。
- P95 有所下降，或者能够用证据定位新的瓶颈。
- 压测报告能够解释为什么远程调用不应位于分布式锁范围内。

## 15. 后续路线

V2 完成后，默认按照以下顺序推进，除非实际测试结果要求调整。

### 阶段 A：RabbitMQ 与订单超时

- 发送订单创建事件。
- 实现生产者 Confirm。
- 实现消费者 ACK。
- 实现消息消费幂等。
- 实现失败重试和死信队列。
- 通过延迟消息触发 15 分钟订单超时关闭。
- 保留数据库定时扫描，作为延迟消息遗漏时的兜底。

延迟消息只是触发器，不是最终事实来源。消费者必须查询订单当前状态，并通过条件更新完成状态流转。

### 阶段 B：订单状态机

- 确认订单。
- 取消订单。
- 超时关闭订单。
- 完成履约。
- 每次有效状态流转写入 `fo_order_status_log`。
- 根据状态流转释放 `locked_stock`，或者将其转移到 `sold_stock`。

### 阶段 C：稳定性与服务治理

- Gateway 路由。
- Sentinel 限流、熔断和降级。
- 超时与重试策略。
- 对异步任务使用独立线程池隔离。
- TraceId 贯穿 HTTP、Feign、MQ 和定时任务。

### 阶段 D：可观测性与问题排查

- 慢接口日志。
- Prometheus/Grafana 或 OpenTelemetry 基础接入。
- 人为构造 OOM、CPU 飙高和死锁场景。
- 记录排查命令、dump 证据、根因和解决过程。

## 16. 与 AgentDesk 的集成边界

FlowOrder 稳定后，需要逐步提供适合 Agent 调用的业务能力：

- `queryOrder(requestId/orderNo)`
- `queryInventory(stockItemId)`
- `createReservation(...)`
- `cancelReservation(orderNo)`
- 后续工单领域的 `createTicket(...)`

要求：

- 查询类工具可以直接执行。
- 创建、取消和状态修改类工具必须具备权限校验，并支持 Human-in-the-loop 人工确认。
- 所有修改状态的接口都必须幂等。
- 返回结构化错误码，让 Agent 能够区分业务失败和传输失败。
- 保留 TraceId，让 AgentDesk 可以关联模型、工具调用和业务系统日志。

## 17. 编码规范

- 新代码优先使用构造器注入；如果修改旧类会造成与任务无关的大范围改动，可以暂时沿用现有注入方式。
- 文件使用 UTF-8，保证中文注释可读。
- 注释重点解释业务不变量、异常语义和设计原因，不要逐句翻译 Java 代码。
- 事务边界放在 Spring 管理的 Service 方法中。
- 依赖 `@Transactional` 时，注意同类内部调用不会经过 Spring 代理。
- 除非有明确理由，不能在本地数据库事务中执行远程调用。
- 不能在远程调用期间持有分布式锁。
- 状态流转必须使用类似 `WHERE status = ?` 的条件更新。
- 所有重试操作都必须幂等。
- 结果未知时，不能捕获异常后伪装成明确业务失败。
- Feign 路径必须与 Controller 路径保持一致。当前订单查询路径为 `/order/query`。
- 修改状态流转、补偿或幂等逻辑时，需要补充针对性的测试。

## 18. 构建与运行检查

当前 V1 依赖的基础设施和服务启动顺序：

1. MySQL。
2. Redis。
3. Nacos。
4. order-service。
5. resource-service。
6. 只有测试路径经过网关时才需要启动 Gateway。

从仓库根目录构建 resource-service：

```powershell
mvn -pl floworder-server/floworder-resource-service -am test
```

修改 Feign DTO 或接口契约后，还需要构建 order-service：

```powershell
mvn -pl floworder-server/floworder-order-service -am test
```

测试服务不可用场景时，需要确认服务确实已经从 Nacos 下线，并检查底层 Feign 异常。进程启动成功不代表服务发现、负载均衡、Controller 路径和序列化一定正常。

## 19. 完成定义

只有同时满足以下条件，一个版本或功能才能算完成：

- 主流程代码已经实现。
- 数据库和 Redis 不变量已经验证。
- 异常、重试和补偿路径已经测试。
- JMeter 或针对性自动化测试覆盖了目标行为。
- 日志包含足够的故障定位信息。
- 能从问题、备选方案、设计取舍和实测结果四个角度解释实现。
- 架构或版本边界发生实质变化时，同步更新 README 和本文件。

项目目标不是证明使用过多少技术，而是证明每个技术都解决了真实工程问题，并且效果可以通过测试数据和系统状态验证。
