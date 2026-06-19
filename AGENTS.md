# FlowOrder 项目协作与学习指南

## 1. 文档作用与事实来源

本文件适用于整个 `floworder` 仓库。任何进入本项目的编码 Agent，在分析、设计、修改或评审代码前，都必须先阅读本文件。

判断项目状态时，事实优先级如下：

1. 当前分支和工作区代码。
2. `sql/floworder.sql`、配置文件和 Maven 依赖。
3. 自动化测试、JMeter、日志、Actuator 和中间件控制台的实际结果。
4. README、设计文档和聊天记录。

讨论过的方案不等于已经实现，代码已经合并也不等于故障路径已经验证。

当前文档快照：2026-06-19。此时 `main` 已合并 V3、V4、V5、V6 代码和 RabbitMQ 消费死信恢复代码。工作区可能有用户尚未提交的修改，开始任务前必须执行 `git status --short`，不得覆盖或回滚无关改动。

## 2. 项目目标

### 2.1 目标岗位与项目边界

`岗位信息.txt` 中与 Java 后端方向重复度最高的要求，不是“会写 Spring Boot 接口”，而是：

- 扎实的 Java、数据结构与算法、操作系统、计算机网络基础。
- 大型分布式系统中的高并发、高性能、高可用和可扩展性设计。
- 对 Spring、MySQL、Redis、MQ 等框架和中间件不只会使用，还能解释原理、边界和优化方法。
- 能完成方案设计、编码、单元测试、性能优化、上线验证和线上问题排查的完整研发流程。
- 能独立拆解问题、比较方案、定位风险，并用数据验证效果。

FlowOrder 的最终定位是：

> 高并发预约交易与履约一致性平台。以预约准入、异步受理、库存预扣、订单履约和异常恢复为业务闭环，用可重复的工程证据证明 Java 后端候选人在并发正确性、最终一致性、可靠消息、高性能、高可用、服务治理和故障排查方面的能力。

FlowOrder 不追求简单业务接口数量。新增业务模块必须能够引出并验证 Java 高级能力、并发控制、事务边界、分布式一致性、可靠消息、性能优化或故障恢复问题。普通 CRUD、普通查询和通用后台能力不进入项目主线。

FlowOrder 主要服务于 Java 后端和 AI 应用后端岗位中的“高可靠业务系统”部分。它不负责覆盖全部 Agent、RAG、Memory、MCP 和 Multi-Agent 能力；这些能力由后续 AgentDesk 项目承担。两个项目应形成互补，而不是把所有 JD 关键词塞进同一个仓库。

### 2.2 FlowOrder 必须证明的核心能力

#### A. 并发正确性，而不只是并发访问

- 解释 V1 分布式大锁为什么正确但吞吐较低。
- 解释 V2 为什么把并发控制下沉到 Redis Lua、MySQL 条件更新和唯一索引。
- 在单实例和多实例下证明无超卖、无负库存、相同 `requestId` 只产生一个业务结果。
- 能说明 JVM 本地锁、分布式锁、CAS、数据库锁分别适合解决什么问题，以及为什么当前主链路不依赖进程内锁保证分布式正确性。
- 线程池必须有容量、拒绝策略、隔离边界和关闭策略，不能只会调用 `@Async`。

#### B. 分布式一致性，而不只是调用中间件

- 区分明确业务失败、传输失败和提交结果未知，禁止把未知结果当成失败释放库存。
- 掌握本地事务边界、事务传播和事务失效场景，避免在数据库事务内执行远程调用。
- 使用 Outbox、幂等消费、条件状态转换和补偿实现最终一致性，并能说明为什么没有直接引入 Seata。
- 面对重复、乱序、延迟、消息丢失、ACK 丢失和服务崩溃时，状态仍能收敛到明确终点。
- 所有自动处理都必须有边界；无法自动确认的结果进入 `MANUAL_REVIEW`，不能永久静默卡在处理中。

#### C. 高性能与可扩展性，而不只是“使用 Redis”

- 使用相同环境和数据对比 V1、V2、V3，记录 Throughput、P95、P99、错误率和资源使用情况。
- 能通过线程池、数据库连接池、Redis、RabbitMQ 积压、锁等待和远程调用耗时定位瓶颈。
- 使用 Explain、索引设计和慢 SQL 证据说明数据库访问是否合理。
- 证明服务可以无状态多实例部署；定时任务和 Outbox 扫描依靠数据库抢占与租约协调，而不是依赖单机假设。
- 优化必须由测量驱动。例如 Outbox Confirm 是否异步化，要先证明同步等待造成积压，再设计有界并发，而不是凭感觉改成异步。

#### D. 高可用与稳定性，而不只是“服务能启动”

- 对 Redis、RabbitMQ、MySQL、Nacos 和 order-service 的不可用、超时和恢复过程进行故障实验。
- 使用限流、熔断、降级、超时、有限重试、隔离和优雅停机控制故障扩散。
- 区分“快速失败”“业务失败”“结果未知”和“需要人工恢复”四种语义。
- 对 Outbox `DEAD`、RabbitMQ DLQ、库存补偿和人工重放建立可操作闭环。
- 设计时承认单点和开发环境限制，不在没有部署证据时宣称真正的生产级高可用。

#### E. 可观测性与故障排查，而不只是打印日志

- `traceId`、`requestId`、`messageId`、`orderNo`、`deductNo` 能贯穿请求、Feign、MQ、定时任务和数据库状态。
- Actuator 指标、结构化日志、RabbitMQ 控制台、SQL 和 Redis 状态可以相互印证。
- 能完成 CPU 飙高、线程阻塞、死锁、OOM、消息堆积、慢 SQL 和连接池耗尽等场景的排查演练。
- 保留线程 dump、堆 dump、GC/系统指标、关键日志、根因和修复前后对比，而不是只背诵排查命令。

#### F. 工程交付能力，而不只是个人 Demo

- 重要改动先有问题定义、约束、方案对比、状态图或时序图，再进入编码。
- 核心状态机、幂等、补偿和消息可靠性必须有针对性自动化测试。
- 使用 Git 分支、清晰提交、PR、自审和代码审查记录保留演进过程。
- 接口、错误码、数据库迁移、配置和恢复操作需要文档化。
- 每个版本能够从“为什么做、怎么设计、有哪些失败方式、如何验证、结果如何”五个方面完整复盘。

### 2.3 JD 能力到项目证据的映射

| JD 能力 | FlowOrder 承载点 | 必须保留的证据 |
| --- | --- | --- |
| Java 并发与锁 | V1/V2 锁范围、线程池隔离、任务抢占 | 并发测试、线程 dump、锁等待和吞吐对比 |
| Spring 与事务 | 本地事务、Feign 边界、消费事务、优雅停机 | 事务回滚测试、事务失效说明、停机日志 |
| MySQL | 条件库存更新、唯一索引、状态机、任务扫描 | 表结构、Explain、并发更新和死锁分析 |
| Redis | Lua 扣减、缓存重建、补偿与失效 | Lua 原子性测试、Redis/MySQL 一致性核对 |
| RabbitMQ | Outbox、Confirm/Return、ACK、DLQ、重放 | 故障注入、消息表状态、重复/乱序测试 |
| 分布式系统 | 幂等、最终一致性、状态机、限流熔断 | 状态收敛记录、异常矩阵、人工恢复流程 |
| 性能与高可用 | JMeter、Sentinel、线程池、优雅停机 | P95/P99、错误率、积压量、恢复时间 |
| JVM/Linux/网络排查 | Actuator、日志、线程/堆 dump、超时实验 | 排查报告、命令输出、根因和修复对比 |
| 工程能力 | 设计文档、测试、Git/PR、复盘 | 可审查提交、测试报告、版本决策记录 |

### 2.4 后续能力分层

#### 必须进入主链路

- V7-lite：预约窗口、资源状态、资格、并发限购和无效请求前置拦截。
- V8：持久化预约请求、有界线程池、`BlockingQueue`、`CompletableFuture`、数据库租约、过载保护和优雅停机。
- V9：事件版本、重复/乱序/迟到处理，以及订单、库存、资格额度和预约请求最终一致。
- V10-core：最小认证/RBAC、`preview/execute`、幂等恢复、领域恢复接口和 AOP 审计。
- Outbox、Publisher Confirm/Return、手动 ACK、幂等消费、DLQ、`MANUAL_REVIEW` 和状态机等现有可靠性机制。
- V12：随版本完成的测试、压测、故障注入和排查证据。

#### 可以作为业务增强

- 经压测证明必要的 V11 热点资源缓存与流量治理。
- 经积压和 Confirm 延迟证明必要的 Outbox 有界并发发布。
- 经故障实验证明确有必要的细粒度恢复动作。

业务增强没有明确问题、基线数据和验收指标时，不进入开发。

#### 只适合独立实验

- AQS、CAS、JMM、锁和并发容器原理。
- `CompletableFuture` 异常传播、线程饥饿和线程池死锁。
- JVM CPU、OOM、死锁、GC 和类加载。
- Spring IOC、AOP、事务失效、Bean 生命周期和自动装配。
- MyBatis 执行流程。
- MySQL MVCC、死锁、索引失效和隔离级别。

独立实验可以使用 FlowOrder 场景说明问题，但不得为了展示 API 而污染业务主链路。

#### 当前不建议做

- 完整资源后台、通用运营后台、商品中心、营销、支付和售后。
- Seata、Kafka 双 MQ、分库分表、复杂座位模型、ES 和 Kubernetes。
- Prometheus/Grafana、OpenTelemetry/SkyWalking 和集中式日志平台。
- Agent、RAG、MCP 或其他与预约交易无关的平台能力。

只有现有方案无法解决已验证问题时，才允许重新评估上述内容。

### 2.5 项目不能替代的并行基础学习

FlowOrder 能提供真实场景，但不能代替系统学习。以下内容需要独立学习、做小实验并形成面试答案：

- 数据结构与算法：复杂度、哈希、树、堆、图、常见算法题。
- Java 基础：集合源码、IO/NIO、反射、泛型、异常和序列化。
- Java 并发原理：JMM、volatile、CAS、AQS、synchronized、ReentrantLock、并发容器、CompletableFuture。
- JVM：内存结构、对象创建、类加载、GC Roots、垃圾回收器和 JVM 参数。
- Spring/MyBatis 原理：Bean 生命周期、循环依赖、AOP、事务传播、MVC 流程、自动装配和 SQL 执行流程。
- MySQL/Redis/MQ 原理：B+ 树、MVCC、锁、缓存问题、Redis 数据结构、RabbitMQ/Kafka 模型差异。
- 计算机网络与 Linux：TCP/IP、HTTP、连接池、超时、进程线程、CPU、内存、磁盘和端口排查。

不要为了展示 AQS、CAS、Kafka、Kubernetes 等知识，强行把它们塞进 FlowOrder 主链路。能在独立实验中讲清原理和取舍，比在项目里留下不必要代码更符合 JD 要求。

### 2.6 项目进度评价标准

评价 FlowOrder 时，不看接口数量和技术名词数量，而看：

- 问题是否来自真实并发、一致性、可靠性或性能约束。
- 方案是否比较过替代设计并说明取舍。
- 正确性是否由不变量、条件更新和测试证明。
- 性能结论是否有同环境、可重复的数据。
- 故障是否能够主动构造、定位并恢复。
- 结果是否能形成设计文档、实验记录和面试表达。

在 DLQ/人工恢复和 V6 故障排查闭环完成前，不扩展 AgentDesk 集成，也不新增与当前问题无关的平台型组件。



### 3.1 默认工作模式

除非用户明确说“请直接修改代码”或“请实现/修复”，否则 Agent 默认只做以下工作：

1. 阅读 AGENTS.md、当前代码、SQL、配置和测试。
2. 输出问题定位、涉及文件、调用链路、方案对比和推荐实现步骤。
3. 指出用户应该先改哪些类、哪些表、哪些测试。
4. 给出关键代码片段或伪代码，但不直接落盘修改。
5. 等用户确认或用户自行编码后，再做代码审查和复查。

如果任务涉及 V7-lite、V8、V9、V10-core 或 V12，Agent 必须先输出设计说明、改造清单、测试清单和风险点，再进入任何代码修改。



### 面试官视角下的项目标准

新增内容要表达：

1. FlowOrder 的简历价值不是来自技术名词数量，而是来自真实实践过程。
2. 每个能力都要能讲清：
   - 具体业务场景是什么；
   - 为什么原方案不够；
   - 为什么选择当前方案；
   - 替代方案是什么；
   - 实现中遇到什么问题；
   - 如何验证正确性、性能和故障恢复；
   - 最后如何复盘。
3. 禁止把项目写成“Redis + MQ + Sentinel + 线程池”的技术清单。
4. 面试表达必须围绕“问题 → 约束 → 方案 → 取舍 → 实现 → 验证 → 复盘”。
5. 即使参考开源项目，也必须真正理解并改造成 FlowOrder 自己的设计，不能背项目、复制代码或堆技术栈。



## 3. 协作方式

用户以亲自编写代码为主要学习方式。

- 默认采用“Agent 讲思路、指出位置、用户编写、Agent 复查”的方式。
- 只有用户明确要求直接实现或修复时，Agent 才编辑业务代码。
- 回答代码问题前先读取当前实现，不依赖旧上下文猜测。
- 一次审查应尽可能完整列出同一范围内的问题，不要每轮只暴露一个容易同时发现的问题。
- 必须区分“已实现”“已验证”“仍有缺口”“未来可选优化”。
- 不直接复制 `damai_pro`。只提取可解释的工程思想，再根据 FlowOrder 的单库存项业务简化。
- 不为了简历堆技术栈。新增组件必须对应明确问题和验证方案。
- 不修改、移动或删除与当前任务无关的用户改动。
- Git 切换分支前保护工作区。不要用强制签出丢弃改动；不要把练习分支的未提交代码通过 Smart Checkout 意外带到 `main`。

## 4. 当前技术栈

以根目录 `pom.xml` 和各服务配置为准：

- Java 17。
- Spring Boot 3.5.14。
- Spring Cloud 2025.0.0。
- Spring Cloud Alibaba 2025.0.0.0。
- MyBatis Plus 3.5.15。
- Redisson 3.32.0。
- MySQL、Redis、RabbitMQ、Nacos。
- OpenFeign、Gateway、Sentinel、Actuator。
- Maven 多模块工程。

默认端口：

- gateway-service：8088。
- resource-service：8081。
- order-service：8082。
- Nacos：8848。
- RabbitMQ：5672。

主要模块：

- `floworder-common`：公共响应、异常和枚举。
- `floworder-server-client`：Feign 契约、共享 DTO 和 MQ 协议。
- `floworder-resource-service`：预约入口、校验、Redis/MySQL 库存、预扣记录、MQ 结果和状态消费。
- `floworder-order-service`：订单创建、查询、状态机、状态日志和订单侧 Outbox。
- `floworder-gateway-service`：统一入口和网关限流。
- `floworder-service-initialize`：组合校验基础设施。
- `floworder-redisson-framework`：Redisson 公共配置。
- `sql`：表结构与测试数据。
- `jmeter`：并发和性能实验。

## 5. 版本路线与当前定位

### V1：历史对照版本

- 接口：`POST /reservation/create/v1`。
- 使用 Redisson 分布式大锁覆盖库存和同步远程调用。
- 用于学习锁范围、看门狗、锁等待以及远程调用持锁的吞吐代价。
- V1 仅作为历史学习样本，不再作为当前正确性基线，也不继续投入收口工作。

### V2：同步正确性基线

- 接口：`POST /reservation/create/v2`。
- Redis Lua 原子检查并扣减库存。
- MySQL 使用条件更新完成 `available_stock -> locked_stock`。
- 同步 Feign 创建订单。
- 明确区分业务失败和远程结果未知。
- 通过查询订单、有限重试和条件状态更新完成确认或补偿。
- V2 是无超卖、无负库存、`requestId` 并发幂等和未知结果处理的最低正确性基线。

### V2.1：可选实验

- 只允许作为 `ReentrantLock` 单实例/多实例对比实验。
- 没有测量收益时，不把 JVM 本地锁加入主链路。

### V3：RabbitMQ 异步下单

- 接口仍为 `POST /reservation/create/v3`。
- 资源服务在同一 MySQL 事务中写库存预扣记录和订单创建 Outbox。
- Outbox 任务发送 RabbitMQ 订单创建命令。
- 订单服务幂等消费，在同一事务中创建订单、写消费日志和订单结果 Outbox。
- 资源服务消费订单结果，确认订单创建或释放锁定库存。
- 包含持久化消息、Publisher Confirm/Return、手动 ACK、消费幂等、有限重试、DLQ 和发送租约。
- 消费死信使用 `fo_mq_dead_letter` 持久化原消息、失败来源、处理状态和人工操作信息；数据库事务提交后才 ACK DLQ。
- 创建命令死信代表订单结果未知，只能把 `PRE_DEDUCTED` 转为 `MANUAL_REVIEW` 并继续锁定库存，不能直接释放库存。
- 死信支持人工重放、消费成功确认、人工忽略和 `REPLAYING` 超时回收；结果/状态死信通过 order-service 原 Outbox 重放，创建命令通过 resource-service 原 Outbox 重放。
- 上述死信闭环代码已经实现并通过编译，但故障注入、重复重放、多实例扫描和业务不变量实验尚未全部完成，因此暂时不能表述为已经生产验证。

### V4：订单状态机和超时闭环

- V4 不新增 `/create/v4`；购买入口继续使用 V3。
- 订单状态：创建、确认、取消、超时。
- 有效状态转换写入 `fo_order_status_log`。
- 订单状态消息驱动资源侧库存最终结算。
- 数据库订单状态是事实来源；定时任务或延迟消息只是触发器。

### V5：稳定性与服务治理

- Gateway 路由与限流。
- Sentinel 远程依赖熔断。
- HTTP、MQ 消费者和定时任务线程池隔离。
- Spring Boot、Tomcat、RabbitMQ 消费线程和数据源优雅停机验证。

### V6：代码级可观测性

- `traceId`/`requestId` 已贯穿 HTTP、Feign、订单创建消息、订单结果消息和状态消息。
- MQ 消费线程使用 MDC 并在 `finally` 中清理。
- 日志格式和基础 Actuator 端点已经接入。
- V6 尚未完成全部实验和文档，不能表述为完整可观测平台。

### 后续主线优先级

以下版本只定义 V1-V6 缺口闭环后的演进方向。第 10 节近期缺口和验证工作未完成前，不提前扩展新业务版本或中间件。

#### V7-lite：预约准入与并发限购

- 只补充预约窗口、最小资源状态、用户资格、并发限购和无效请求前置拦截。
- 资源侧只保留发布、关闭等必要状态命令，不建设资源 CRUD 或管理后台。
- Redis 只用于快速拒绝；资源状态、资格和额度的事实来源仍是 MySQL。
- 并发额度必须由 MySQL 条件更新、唯一索引和本地事务保证，不能只依赖先查后写。

#### V8：高并发异步预约引擎

- V8 是后续核心版本，必须进入业务主链路。
- 预约请求先持久化，再通过数据库租约、有界 `ThreadPoolExecutor`、显式 `ArrayBlockingQueue` 和隔离的 `ThreadPoolTaskExecutor` 处理。
- `CompletableFuture` 只用于互不依赖的只读校验；库存扣减、额度更新、事务提交和 Outbox 写入不得拆到多个异步线程。
- JVM 队列只负责进程内调度。分布式库存正确性继续由 Redis Lua、MySQL 条件更新、唯一索引和状态机保证。
- 必须验证队列满、拒绝恢复、MDC 传递与清理、租约回收、多实例竞争、过载保护和优雅停机。

#### V9：订单履约与状态机增强

- 基于 V4 增加事件版本、发生时间、重复/乱序/迟到处理，并接通 V8 预约请求结果。
- 不重新实现现有确认、取消和超时功能。
- 必须证明订单、库存、资格额度和预约请求在重复投递、ACK 丢失和消息乱序下最终收敛。

#### V10-core：恢复控制面核心版

- 新增轻量 recovery-service 时，它只负责认证、最小 RBAC、AOP 审计和恢复编排。
- 恢复动作采用 `preview -> execute`，使用唯一 `actionRequestId`、期望状态/版本和操作原因防止误操作及重复执行。
- 订单恢复必须调用 order-service 领域接口；库存释放、转销售和核对必须调用 resource-service 领域接口。
- recovery-service 不直接修改订单或库存领域表，不扩展为通用运营后台。

#### V11：可选热点缓存增强

- V11 不是主线必做版本。
- 只有 V8/V12 压测证明热点读成为瓶颈，且 Redis TTL、空值缓存和请求合并仍不足时，才允许引入 Caffeine 本地缓存。
- 缓存只能优化资源读链路，不能参与库存、额度和预约状态的最终正确性判断。

#### V12：贯穿式工程证据建设

- V12 不是最后补文档的版本，而是贯穿 V7-lite、V8、V9 和 V10-core。
- 每完成一个主线阶段，必须同步补充正确性测试、压测或容量测试、故障注入、Explain/慢 SQL、线程 dump 或关键运行日志。
- 综合证据必须覆盖 V1/V2/V3/V8 同环境比较、线程池队列与拒绝、RabbitMQ 积压、Outbox/DLQ 恢复时间以及 Redis/MySQL/Nacos/order-service 故障。

## 6. 核心领域边界

resource-service 拥有：

- 资源和库存项校验。
- Redis 库存缓存。
- `fo_stock_item`。
- `fo_stock_deduct_record`。
- 订单结果和订单状态消息对应的库存处理。

order-service 拥有：

- `fo_reservation_order`。
- 订单创建、确认、取消和超时状态机。
- `fo_order_status_log`。
- 订单状态事件生产。

即使开发环境共用一个 MySQL，也不能跨服务随意修改对方领域表。

## 7. 状态、不变量与幂等

库存始终满足：

```text
total_stock = available_stock + locked_stock + sold_stock
```

任何库存字段不得为负。

订单状态：

- `0 INIT`：初始化。
- `10 RESERVED`：已预约。
- `20 CONFIRMED`：已确认。
- `30 CANCELLED`：已取消。
- `40 TIMEOUT`：已超时。
- `50 FAILED`：创建失败。

库存预扣状态：

- `10 PRE_DEDUCTED`：已预扣，库存位于 `locked_stock`。
- `20 ORDER_CREATED`：订单已创建，库存仍锁定。
- `30 RELEASED`：库存已释放回 `available_stock`。
- `40 FAILED`：处理失败。
- `50 MANUAL_REVIEW`：结果不确定，等待人工处理，库存仍锁定。
- `60 SOLD`：库存已转入 `sold_stock`。

Outbox 状态：

- `0 NEW`。
- `10 SENDING`。
- `20 SENT`。
- `30 RETRY`。
- `40 DEAD`。

消费死信状态：

- `0 PENDING`：已从 RabbitMQ DLQ 持久化，等待处理。
- `10 REPLAYING`：已抢占并发起原 Outbox 重放，等待业务结果确认。
- `20 RESOLVED`：结果/状态消息完成消费，或者扫描确认业务状态已经收敛。
- `30 IGNORED`：经人工确认后忽略；必须记录处理人和原因。

死信状态和库存状态不是一回事。死信进入 `RESOLVED` 只表示对应恢复动作已经完成；库存仍必须满足领域状态机和库存恒等式。

幂等要求：

- 相同 `requestId` 必须对应同一用户、资源、库存项、数量和订单参数。
- 数据库唯一索引是最终防线，应用层仍需校验重复请求参数一致性。
- MQ 使用 `messageId + consumerGroup` 唯一约束。
- 状态转换必须带原状态条件，例如 `WHERE status = PRE_DEDUCTED`。
- ACK 丢失、Confirm 结果未知和重复投递都可能产生重复消息，消费者必须允许安全重放。

## 8. 两类 MQ 消费者的职责

`OrderResultConsumer` 处理“订单是否创建成功”：

```text
创建成功：PRE_DEDUCTED -> ORDER_CREATED
创建失败：PRE_DEDUCTED -> RELEASED
```

创建成功时只确认订单存在，不扣减 `locked_stock`。代码中的 `confirm` 更接近 `markOrderCreated` 语义。

`OrderStateConsumer` 处理订单创建后的生命周期：

```text
确认订单：ORDER_CREATED -> SOLD
取消/超时：ORDER_CREATED -> RELEASED
```

状态消息可能早于订单创建结果到达，因此状态消费者允许从 `PRE_DEDUCTED` 直接转为 `SOLD` 或 `RELEASED`。迟到的创建成功结果不得覆盖 `RELEASED` 或 `SOLD` 终态。

`fo_order_status_log` 由 order-service 写入。resource-service 的状态消费者不负责写订单状态日志。

## 9. RabbitMQ 与 Outbox 可靠性规则

- 业务数据和 Outbox 必须在同一个本地事务中提交。
- 只有 Broker ACK 且消息可路由时，Outbox 才能从 `SENDING` 转为 `SENT`。
- 发布失败进入退避重试，超过次数进入 `DEAD`。
- 发送任务必须先条件抢占记录，并使用 `claimUntil` 回收崩溃实例留下的租约。
- 消费业务、消费日志和结果 Outbox 必须在同一个本地事务中提交。
- 业务失败可以生成明确失败结果；技术异常不能伪装成业务失败。
- 消费者有限重试后进入 DLQ，不允许无限阻塞消费线程。
- Redis 缓存清理失败时不能直接 ACK；应依靠消息重投继续删除缓存。
- RabbitMQ DLQ 与 Outbox `DEAD` 是两种故障：前者表示 Broker 已接收但消费者最终失败，后者表示生产端可靠发送失败，排查和恢复入口不能混用。
- DLQ 消息必须先完整写入 `fo_mq_dead_letter`，再 ACK RabbitMQ；落库失败时重新入队，不能丢弃唯一副本。
- 创建命令死信的重放由本地事务原子完成：死信抢占、`MANUAL_REVIEW -> PRE_DEDUCTED` 和 resource-service Outbox 恢复发送必须一起提交。
- 结果/状态死信需要 Feign 调用 order-service 恢复原 Outbox。远程调用不得放进数据库事务；响应丢失时依靠相同 `messageId`、Outbox 状态幂等和消费幂等承受重复调用。
- 只有业务处理、Redis 缓存删除和死信解决状态均成功后，普通消费者才能 ACK。
- `REPLAYING` 超时后，扫描任务重新开放处理权；创建命令同时恢复为 `MANUAL_REVIEW`，库存仍保留在 `locked_stock`。

### 9.1 消费死信代码学习顺序

学习时按消息生命周期阅读，不按文件创建顺序阅读：

1. 从 `OrderCreateConsumer`、`OrderResultConsumer`、`OrderStateConsumer` 的有限重试和 `basicNack(..., false, false)` 开始，理解消息如何进入 DLQ。
2. 阅读三个 Rabbit 配置和 `OrderMqConstant`，画出普通队列、DLX、dead routing key、DLQ 的路由关系。
3. 阅读 `MqDeadLetterConsumer`，理解原始消息、`messageId`、`consumerQueue`、`x-death`、事务提交和 ACK/NACK 顺序。
4. 阅读 `MqDeadLetterServiceImpl.record()`、`buildEntity()`、`isolateUncertainCreate()`，理解为什么结果未知只能转人工审核而不能释放库存。
5. 阅读 `replay()`、`claimReplay()`、`replayCreate()` 和两侧 `replayConsumerDead()`，重点解释 `TransactionTemplate` 如何避免把 Feign 放进数据库事务。
6. 阅读 `resolveOrderResult()`、`resolveOrderState()` 以及两个普通消费者中的调用位置，确认解决死信发生在 Redis 删除之后、ACK 之前。
7. 最后阅读 `ignore()`、`isBusinessConverged()`、`recoverStaleReplaying()` 和 `MqDeadLetterMonitorTask`，理解人工兜底、超时回收和基础告警边界。

学习时至少手画 `PENDING -> REPLAYING -> RESOLVED/IGNORED` 状态图，并分别跟踪创建命令、创建结果和订单状态三类死信。不要只记类名和注解。

当前发布器在后台线程中同步等待最多 5 秒的 Publisher Confirm。这不会阻塞用户请求，是简单可靠的基线。若要异步化，优先采用有界发布线程池；真正的异步 Confirm 回调还必须具备：专用有界执行器、最大在途数量、超时、租约协调、拒绝处理和优雅停机。不能只删除 `getFuture().get()` 或直接添加无界 `@Async`。

## 10. 当前已知风险与验证债务

以下内容尚未形成完整闭环，不能在简历中包装为已完成，但不再全部作为 V7-lite/V8 的前置阻塞项。

### 10.1 必须优先修复的小缺陷

1. `MqDeadLetterServiceImpl.isBusinessConverged()` 对订单状态死信的判断存在正确性风险：订单状态死信不能把所有非 `ORDER_CONFIRMED` 事件都按释放库存处理。只允许 `ORDER_CANCELLED` 和 `ORDER_TIMEOUT` 对应 `RELEASED`，未知事件必须返回未收敛，避免被错误标记为 `RESOLVED` 或允许非强制忽略。

该问题属于小范围正确性修复。建议在进入 V7-lite/V8 前完成修复和最小协议异常测试，但不得扩展为完整 DLQ 平台重构。

### 10.2 不阻塞 V7-lite/V8 的验证债务

以下内容作为 V12 贯穿式工程证据的一部分逐步补齐，不再阻塞 V7-lite/V8 的业务演进：

1. RabbitMQ 消费死信的系统化验证：三类 DLQ 故障注入、重复/并发重放、Feign 响应丢失、`REPLAYING` 超时、多实例扫描和库存不变量。
2. 当前“告警”只是定时任务 `ERROR` 日志，不能描述为完整生产告警平台。
3. 死信管理接口依赖 `floworder.admin.enabled=true`，目前没有认证、授权和操作人身份可信校验；正式暴露前必须进入 V10-core 恢复控制面。
4. V6 仍需完成 Actuator 实验记录、压力下线程 dump 分析和 `docs/v6-observability-troubleshooting.md`。
5. Outbox 同步等待 Confirm 是否需要并发化，必须先用积压量、发送吞吐、确认延迟和故障恢复时间证明瓶颈。没有瓶颈证据时保持当前同步 Confirm 基线。

### 10.3 编码时必须保留的风险原则

对数据库事务的通用异常进行 Redis 补偿时，要区分“确定回滚”和“提交结果未知”。结果未知时优先删除 Redis key，从 MySQL 重建，避免盲目增加库存。

### 10.4 当前阶段优先级

在时间紧张的情况下，当前优先级调整为：

1. 修复 `isBusinessConverged()` 的小范围正确性风险并补最小测试。
2. 启动 V7-lite：预约准入与并发限购。
3. 启动 V8：高并发异步预约引擎。
4. 将 DLQ、Outbox、V6 可观测性和故障排查证据纳入 V12 随版本补齐。

除非发现会破坏库存不变量、幂等性或主链路正确性的阻塞 bug，否则不再因为 V3～V6 的验证债务推迟 V7-lite/V8。

## 11. 已验证实验与简历证据

V5 已验证：

- Gateway 对 `/api/reservation/create/v3` 的连续请求返回 HTTP 429，证明网关限流生效。
- Feign 熔断规则实际资源名为 `floworder-order-service`，不是手写的方法签名。使用 Sentinel `getRules` 和 `cnode` 观察到 blocked 数增长和快速失败。
- order-service 不可用且结果未知时，resource-service 保持“结果确认中，请勿重复提交”语义，不立即释放库存。
- IntelliJ Stop 下观察到 Tomcat 等待活动请求、RabbitMQ 等待消费线程、Hikari 正常关闭，证明核心优雅停机路径生效。
- 停机阶段出现过 Nacos `NacosGracefulShutdownDelegate` 空指针，应单独记录为客户端兼容或关闭顺序问题，不能据此否定 Tomcat、RabbitMQ 和数据源的优雅停机结果。

V6 已观察到 HTTP 请求和 MQ 创建/结果链路中的 `traceId`、`requestId` 传播。后续还需要把证据整理为故障排查文档。

简历描述必须包含：工程问题、设计、关键取舍、测试方法和结果。不要只写“使用 Redis/RabbitMQ/Sentinel”。

## 12. 测试原则

正确性测试至少覆盖：

- 单请求成功。
- 相同 `requestId` 顺序和并发重复。
- 相同 `requestId` 携带不同参数。
- 库存不足和 `quantity > 1`。
- 多实例并发下无超卖、无负库存。
- Redis key 缺失、Redis 补偿失败和缓存删除失败。
- MySQL 条件更新失败和事务回滚。
- Feign 业务失败、连接失败、超时、空响应和结果未知。
- RabbitMQ NACK、Return、Confirm 超时、重复投递、消费异常、DLQ 和 Outbox 租约回收。
- 消费死信落库成功/失败、创建命令转人工审核、并发抢占、重放次数上限、远程重放响应丢失、业务收敛确认、强制忽略和超时回收。
- 订单确认、取消、超时以及消息乱序。
- 服务停止时正在执行 HTTP、MQ 和定时任务。

每次库存测试后都校验 MySQL、Redis 和消息表，不能只看 HTTP 200。

性能实验记录：Throughput、Average、P90/P95/P99、Max、HTTP 错误率、业务错误率、库存不变量和消息积压。正式压测关闭 JMeter `View Results Tree`。

已有针对订单状态机并发、MQ 状态消费和部分死信落库/ACK 行为的测试，但死信重放、忽略、超时回收和多实例竞争测试仍不完整。新增或修改状态、补偿、幂等、DLQ、Outbox 逻辑时必须增加对应测试，不能用编译通过或上下文启动测试代替业务断言。

### 12.1 证据状态规则

所有能力必须明确标记为以下状态之一：

- 已实现。
- 已自动化测试。
- 已压测。
- 已故障验证。
- 计划验证。

代码合并不等于验证完成，README 或设计文档中的描述不等于运行证据。简历只能使用已有代码、测试、压测、故障演练或日志记录支撑的结论；未验证内容只能写成“计划验证”。

V7-lite、V8、V9 和 V10-core 每完成一个阶段，都必须同步补充正确性测试、性能或故障实验、关键日志及复测结论，禁止统一拖到最后补做。

## 13. 构建、启动与排查

常用构建命令：

```powershell
mvn -pl floworder-server/floworder-resource-service,floworder-server/floworder-order-service -am clean compile
mvn -pl floworder-server/floworder-resource-service,floworder-server/floworder-order-service -am test
```

基础设施和服务启动顺序：

1. MySQL。
2. Redis。
3. RabbitMQ。
4. Nacos。
5. order-service。
6. resource-service。
7. gateway-service。

排查顺序优先从请求日志、数据库状态、Outbox、消费日志、RabbitMQ 队列/DLQ、Redis 库存到线程 dump，避免只根据一个异常文本下结论。

## 14. 编码约束

- 文件统一 UTF-8。
- 优先使用构造器注入；旧代码大范围改造不应混入无关任务。
- 注释解释不变量、异常语义和设计原因，不逐句翻译代码。
- 本地数据库事务内不执行远程调用。
- 远程调用期间不持有 JVM 锁或分布式锁。
- Spring 事务方法注意同类调用绕过代理问题。
- 不用 `Thread.sleep`、无界线程池或无限重试掩盖流控问题。
- 不把 MQ ACK 当作业务事务的一部分；先完成本地事务，再 ACK。
- 不因消息乱序覆盖更晚的终态。
- 不让补偿异常覆盖原始异常，必要时使用 suppressed exception。
- 删除 Redis key 是数据库已成为事实来源后的安全降级手段，不要在 key 缺失时用 `INCRBY` 创建错误库存。

## 15. 技术路线防漂移

### 15.1 新技术准入规则

任何新框架、中间件或基础设施进入 FlowOrder 前，必须回答：

1. 当前方案无法解决的明确问题是什么。
2. 问题是否已有压测、故障或运行数据证明。
3. 新技术的预期收益及量化验收指标是什么。
4. 引入后的开发、部署、维护和故障成本是什么。
5. 不引入时有哪些替代方案。
6. 如何回退。
7. 最终能形成什么可验证的简历证据。

缺少明确问题或基线数据时，默认不引入。V11 本地缓存和 Outbox 有界并发发布必须严格执行该规则。

### 15.2 当前禁入范围

在当前缺口关闭和系统化复盘完成前，不主动增加：

- Seata。
- 分库分表。
- 多库存项加锁和复杂座位模型。
- Kafka 与 RabbitMQ 双 MQ。
- Kubernetes。
- Prometheus/Grafana。
- OpenTelemetry/SkyWalking。
- 集中式日志平台。

需要这些技术时，先写清当前方案无法解决的问题、引入成本、替代方案和验证指标。

`damai_pro` 仅用于比较工程思路，例如锁粒度、幂等、消息处理和延迟关闭。FlowOrder 不迁移大麦特有的节目、票档、座位和营销模型。

### 15.3 外部调研使用边界

- JD 用于确定能力方向，不能直接决定技术选型。
- 简历项目用于学习问题表达和证据形式，不能因为他人使用某项技术就复制到 FlowOrder。
- 业务型开源项目优先用于学习真实链路；框架和基础设施项目只补充机制思想。
- 外部项目中的完整商城、支付、营销、分片、复杂座位和平台能力默认不迁移。
- 每项调研结论必须说明可迁移思想、FlowOrder 适配方式、不适用部分和验证条件。
- 当前代码、业务不变量、测试、压测和故障结果始终高于外部项目方案。

### 15.4 本地参考项目使用规则

多个开源项目存在于 `D:\JDK\IDEA\java_reinforcement_learning` 目录之下，Agent 可以只读分析其中的 Java 业务型开源项目，用于比较模块划分、表结构、状态机、线程池、MQ、缓存、压测和故障恢复设计。

参考项目只能作为“工程思想和表达方式”的来源，不能作为 FlowOrder 的代码来源。任何借鉴都必须先说明：

1. 参考项目解决的具体问题。
2. FlowOrder 中对应的问题是否真实存在。
3. 可迁移的思想是什么。
4. 需要删除或简化的复杂度是什么。
5. 不适合迁移的内容是什么。
6. 如何在 FlowOrder 中验证收益。
7. 参考项目不是拿来复制或背诵的，而是用来学习工程思想和表达方式。

   每次参考本地项目，都必须回到 FlowOrder 自己的问题，不允许直接搬业务模型。

   对参考项目的使用必须形成：

   - FlowOrder 中对应的问题；
   - 可借鉴思想；
   - 被删除的复杂度；
   - 不迁移内容；
   - 验证方式；
   - 最终是否能形成简历证据。

#### 业务型参考项目

优先分析以下业务型 Java 项目：

| 参考项目 | 主要参考点 | 不迁移内容 |
| --- | --- | --- |
| `12306` | 幂等 starter、缓存、购票链路、线程池拒绝处理、高并发票务准入 | 分片、支付、完整票务领域模型 |
| `damai_pro` | `ArrayBlockingQueue`、线程池参数化、多版本下单、动态压测、延迟取消 | 复杂座位、Kafka、分片、支付、节目票档营销模型 |
| `miaosha` | 秒杀入口分层、Redis 预减、RabbitMQ、限流、分布式锁对照 | 培训式技术堆砌、TCC、无验证的复杂组件 |
| `springboot-seckill` | 本地标记、Redis、RabbitMQ、唯一索引、乐观锁、快速返回/轮询 | 单体 Demo 结构、薄弱的可靠消息和恢复链路 |
| `mall` | 锁定库存、超时订单、延迟消息、订单/库存表设计 | 商城 CRUD、营销、支付、搜索、后台管理 |
| `mall-swarm` | Gateway、Nacos、RabbitMQ、微服务拆分方式 | 技术栈展示式架构、与正确性无关的商城能力 |
| `litemall` | 订单状态、未支付超时、取消返库存、事务处理 | 商城、售后、进程内不可靠任务 |
| `Shopizer` | 领域分层、Facade、测试组织方式 | 完整电商领域、高并发和 MQ 一致性之外的复杂模型 |
| `spring-petclinic-microservices` | Gateway、Circuit Breaker、Tracing、JMeter 实验组织 | 非交易一致性业务、完整监控栈迁移 |
| `eventuate-tram-sagas-examples-customers-and-orders` | Saga、额度预留、PENDING/APPROVED/REJECTED 状态收敛、端到端测试 | Eventuate 框架、CDC、Kafka、完整 Saga 基础设施 |

#### 框架和基础设施参考项目

框架类项目只作为机制思想补充，不作为 FlowOrder 架构迁移依据：

| 参考项目 | 主要参考点 | FlowOrder 决策 |
| --- | --- | --- |
| `RocketMQ` | 重试、DLQ、消息轨迹、消息积压语义 | 只借鉴故障分类和排查思路，继续使用 RabbitMQ |
| `Sentinel` | 资源粒度、流控规则、熔断状态、降级语义 | FlowOrder 已使用 Sentinel，重点补运行证据 |
| `Hippo4j` | 线程池容量、拒绝策略、运行指标和动态调参思想 | V8 首版先使用静态配置；没有明确瓶颈前不引入动态线程池平台 |

#### 使用边界

- 不把参考项目加入 FlowOrder Maven 模块。
- 不复制参考项目源码到 FlowOrder。
- 不因为参考项目使用某项技术，就直接引入该技术。
- 不迁移完整商城、支付、营销、复杂座位、分片、搜索、监控平台或云原生平台能力。
- 优先借鉴业务链路、表结构取舍、线程池参数组织、测试方式、压测记录、异常路径和简历表达方式。
- 每次借鉴后必须形成 FlowOrder 自己的设计说明、测试方案和验证指标。

## 16. 完成定义

一项功能只有同时满足以下条件才算完成：

- 主流程和异常流程均已实现。
- 状态转换、事务边界和幂等条件可解释。
- 数据库、Redis 和消息不变量已验证。
- 重试、补偿、死信和人工恢复路径有明确终点。
- 有针对性自动化测试或可重复实验。
- 日志和指标足以定位故障。
- 能说明为什么没有选择更复杂或更简单的方案。
- 结论有代码、测试数据或运行证据支持。

项目最终目标不是证明使用过多少技术，而是证明能围绕并发、一致性、可靠性、服务治理和故障排查完成一套可验证的工程闭环。



简历内容必须来自已完成的代码、测试、压测、故障注入、Explain、日志、线程 dump 或复盘文档。

不能把“计划验证”“设计方案”“代码已写但未跑通”写成已完成成果。

面试时必须能回答：

- 为什么做；
- 为什么不用更简单方案；
- 为什么不用更复杂方案；
- 压测数据是什么；
- 异常路径怎么处理；
- 最终怎么证明它有效。
