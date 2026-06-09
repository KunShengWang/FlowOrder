# FlowOrder
应对中大厂实习的高并发项目


# FlowOrder 项目实施规格

## 项目定位

FlowOrder 是一个高并发预约/订单/库存/任务履约平台，用来证明传统 Java 后端能力。

项目面向有限资源预约场景，例如服务名额、设备资源、执行窗口。用户抢预约后，系统完成库存扣减、订单状态流转、异步履约任务派发、超时关闭和最终一致性补偿。

## 技术栈

- Java 21
- Spring Boot 3.x
- Spring Cloud Alibaba
- MyBatis Plus
- MySQL 8
- Redis 7
- RabbitMQ
- Redisson
- Nacos
- Gateway
- Sentinel
- Docker Compose
- JMeter

Kafka、K8s、复杂分库分表不进入第一版实现。

## 核心模块

### 用户与权限

功能：

- 用户注册、登录。
- JWT 鉴权。
- RBAC 权限模型。
- 管理员、普通用户、运营人员三类角色。

需要掌握：

- Spring Security 或轻量 JWT 鉴权。
- 拦截器/过滤器区别。
- RBAC 表设计。

### 资源与库存

功能：

- 创建资源。
- 配置预约窗口。
- 配置库存/名额。
- 查询可预约资源。

核心表：

- `resource`
- `resource_slot`
- `resource_inventory`

需要掌握：

- MySQL 表结构设计。
- 联合索引。
- 资源状态字段设计。

### 订单状态机

状态：

- `INIT`
- `RESERVED`
- `CONFIRMED`
- `FULFILLING`
- `FINISHED`
- `CANCELLED`
- `TIMEOUT_CLOSED`
- `FAILED`

要求：

- 所有状态流转必须校验前置状态。
- 状态变更记录进入订单流水表。
- 取消、超时、失败都要考虑库存回滚。

核心表：

- `reservation_order`
- `order_status_log`

需要掌握：

- 状态机设计。
- 乐观锁。
- 事务边界。

### 高并发预约

流程：

1. 校验用户是否重复预约。
2. 查询 Redis 库存。
3. Lua 原子预扣库存。
4. 创建订单。
5. 发送 MQ 履约任务。
6. 失败时补偿库存。

要求：

- Redis 预扣防超卖。
- MySQL 唯一索引防重复预约。
- 订单创建接口支持幂等。
- JMeter 压测验证库存不为负。

需要掌握：

- Redis Lua。
- Redisson 分布式锁。
- 缓存一致性。
- 接口幂等。

### MQ 异步履约

消息类型：

- `ORDER_CREATED`
- `ORDER_TIMEOUT_CLOSE`
- `FULFILLMENT_TASK_CREATED`
- `ORDER_COMPENSATE`

要求：

- 生产者确认。
- 消费者手动确认。
- 消息幂等表。
- 死信队列。
- 延迟队列处理超时关闭。
- 失败重试和补偿。

核心表：

- `message_consume_log`
- `fulfillment_task`

需要掌握：

- RabbitMQ ack/confirm。
- 重复消费。
- 死信队列。
- 延迟队列。
- 最终一致性。

### 稳定性与治理

功能：

- Sentinel 限流。
- 接口降级。
- 线程池隔离。
- 超时控制。
- 慢接口日志。
- TraceId 贯穿请求、MQ、任务。

要求：

- 高并发预约接口配置限流规则。
- MQ 消费使用独立线程池。
- 日志必须能追踪一次订单全链路。

需要掌握：

- ThreadPoolExecutor 参数。
- 拒绝策略。
- Sentinel 限流降级。
- 日志链路追踪。

## API 清单

用户：

- `POST /api/auth/login`
- `GET /api/users/me`

资源：

- `POST /api/resources`
- `GET /api/resources`
- `POST /api/resources/{id}/slots`
- `POST /api/resources/{id}/inventory`

预约订单：

- `POST /api/orders/reserve`
- `GET /api/orders/{orderNo}`
- `POST /api/orders/{orderNo}/cancel`
- `POST /api/orders/{orderNo}/confirm`

任务：

- `GET /api/tasks`
- `POST /api/tasks/{id}/retry`

监控与演示：

- `GET /api/demo/inventory/{slotId}`
- `GET /api/demo/orders/{orderNo}/trace`
- `GET /actuator/health`

## 表结构设计要点

必须有：

- 用户表、角色表、用户角色表。
- 资源表、预约窗口表、库存表。
- 订单表、订单状态流水表。
- 履约任务表。
- 幂等请求表。
- 消息消费日志表。

关键索引：

- `reservation_order(order_no)` 唯一。
- `reservation_order(user_id, slot_id)` 唯一，防重复预约。
- `resource_inventory(slot_id)` 唯一。
- `message_consume_log(message_id, consumer_group)` 唯一。
- `order_status_log(order_no, created_at)` 普通索引。

## 参考项目阅读范围

### mall

只看：

- 订单模块。
- 会员/权限模块。
- Redis 使用。
- RabbitMQ 使用。
- 表结构和分层方式。

不看：

- 前端。
- 商品详情细节。
- 营销活动细节。

### mall-swarm

只看：

- Gateway。
- Nacos。
- 认证中心。
- 监控和部署结构。

不深挖：

- 全量微服务源码。
- K8s 部署细节。

### xxl-job

只看：

- 任务模型。
- 执行器。
- 失败重试。
- 任务日志。

### Sentinel

只看：

- 限流、熔断、降级使用方式。
- 规则配置和监控指标。

## 验收清单

- 高并发预约压测不超卖。
- 重复请求不会重复创建订单。
- MQ 重复投递不会重复消费。
- 订单超时能自动关闭并回滚库存。
- Sentinel 限流生效。
- 能输出压测报告：QPS、平均 RT、P95、错误率。
- 能演示一次 TraceId 从 HTTP 请求到 MQ 消费再到任务落库。
- 能讲清 OOM、CPU 飙高、死锁的排查步骤。

## 简历表达草案

- 设计并实现高并发预约订单系统，基于 Redis Lua 进行库存预扣，结合 MySQL 唯一索引与接口幂等表防止重复预约和库存超卖。
- 引入 RabbitMQ 实现订单履约异步化，设计消息消费幂等、失败重试、死信队列和订单超时关闭机制，保障最终一致性。
- 使用 Sentinel、线程池隔离和 TraceId 日志链路提升系统稳定性，并通过 JMeter 压测定位瓶颈，完成核心接口性能优化。

