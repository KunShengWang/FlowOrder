# FlowOrder 简历要点草稿

项目名称建议：

```text
FlowOrder：高并发预约交易与履约一致性平台
```

一句话介绍：

```text
围绕预约准入、异步受理、库存预扣、订单履约和异常恢复，设计并实现高并发预约交易链路，重点解决并发正确性、可靠消息和最终一致性问题。
```

可写要点：

1. 设计预约准入与限购链路，基于 Redis Lua、MySQL 条件更新、唯一索引和 requestId 幂等保证并发下无超卖、无负库存和重复请求结果一致。

2. 基于 Outbox + RabbitMQ 构建异步下单链路，结合 Publisher Confirm、手动 ACK、消费幂等和 DLQ 处理，保证订单创建消息可追踪、可重试、可恢复。

3. 引入持久化预约请求表、数据库租约抢占和有界线程池，实现高并发异步预约处理；JMeter 100 并发、900 请求下 HTTP 错误率 0%，请求、订单、库存和消息状态最终收敛。

4. 建立订单履约状态机，订单确认时将锁定库存转为成交库存，订单取消/超时时释放库存和用户额度，并通过 requestId 查询最终履约状态。

5. 设计不可变 Proposal -> 人工审批 -> 幂等执行 -> 业务回查的受控恢复闭环，由 FlowOrder 持有 Proposal 与 Action 权威状态；分离 proposalId/actionRequestId，通过 EXECUTING 租约和原 Action 对账处理响应丢失与崩溃窗口，并区分命令提交与业务收敛结果。

面试展开关键词：

```text
Redis Lua 原子性
MySQL 条件更新
唯一索引幂等
Outbox 可靠消息
RabbitMQ 消费幂等
DLQ 恢复
数据库租约
有界线程池
订单状态机
库存恒等式
Proposal 版本化审批
actionRequestId 业务幂等
Action 执行租约与 CAS 接管
UNKNOWN 原 ID 对账
命令状态与业务结果分离
```

不要写：

```text
生产级高可用
完整运营后台
完整权限系统
多级缓存优化
分库分表
Kubernetes 部署
```

这些当前没有完整证据，面试中容易被追问穿。
