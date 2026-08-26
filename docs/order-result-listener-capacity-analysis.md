# 订单结果 Listener 容量分析

## 1. 结论

订单结果队列积压和`Consumer failed to start in 60000 milliseconds`的根因不是Rabbit连接、channel、Hikari或业务协议，而是resource-service专用Rabbit listener executor的线程池排队语义与长生命周期consumer任务不匹配。

修复前：

```text
concurrentConsumers=2
maxConcurrentConsumers=4
executor core=2 / max=4 / queue=100
```

两个基础consumer永久占用core线程。积压触发Spring AMQP动态扩容时，第3/4个consumer任务先进入容量100的队列；`ThreadPoolExecutor`在队列未满时不会创建非core线程，而基础consumer又不会结束，导致扩容任务无法开始。Spring AMQP等待默认60秒后记录startup timeout，Rabbit实际consumer数仍为2。

最小修复是保留2～4的有界动态consumer设计，将长生命周期listener executor改为零队列：

```text
executor core=2 / max=4 / queue=0
```

After结果证明executor可以直接扩到4，queue始终为0；40/50 req/s持续输入期间order result publish、consume、commit速率对齐，result queue ready最大为0，没有consumer startup timeout。50 req/s可以作为完整订单结果回写链路的正式高档target。

## 2. 完整调用链

```text
floworder.order.result.queue
  -> OrderResultConsumer.consume
  -> OrderResultMessageServiceImpl.handle (@Transactional)
     -> 按deductNo查询fo_stock_deduct_record
     -> 写fo_mq_consume_log(status=0，messageId+consumerGroup唯一)
     -> 成功结果：PRE_DEDUCTED -> ORDER_CREATED
     -> 更新fo_reservation_request.order_status=10
     -> fo_mq_consume_log 0 -> 10
     -> 事务提交
  -> 必要时删除Redis库存缓存/更新请求释放状态
  -> 解决对应dead letter恢复状态
  -> basicAck
```

失败结果仍在同一事务中按原顺序归还quota、`PRE_DEDUCTED -> RELEASED`、恢复库存并更新request失败状态。协议校验失败`basicReject(..., false)`；技术异常最多本地重试3次，最终`basicNack(..., false, false)`进入DLQ。

本次没有改变手动ACK位置、事务边界、幂等唯一键、重试/DLQ语义或MQ协议，仍是at-least-once delivery。重复成功结果由`message_id + consumer_group`唯一约束和已消费状态短路，不产生重复副作用。

## 3. Listener、Container与Executor配置

| 项目 | 配置 |
|---|---|
| Listener | `OrderResultConsumer.consume`上的`@RabbitListener` |
| Queue | `floworder.order.result.queue` |
| Factory | `orderResultListenerContainerFactory` |
| Container | `SimpleRabbitListenerContainerFactory` |
| concurrent/max | 2/4 |
| prefetch | 10 |
| batch | 未启用，逐条消费 |
| ACK | MANUAL |
| requeue rejected | false |
| Executor | 独立`orderResultConsumerExecutor` |
| Before executor | core=2/max=4/queue=100 |
| After executor | core=2/max=4/queue=0 |

result、state和dead-letter各有独立executor；它们不与scheduler、V8 worker、Outbox publisher或HTTP线程共享。order-service的order-create listener此前已经采用`queueCapacity=0`，没有同类startup timeout。

Spring Rabbit 3.2.10当前默认动态参数由本地依赖字节码确认：

```text
startConsumerMinInterval=10000ms
stopConsumerMinInterval=60000ms
consumerStartTimeout=60000ms
consecutiveActiveTrigger=10
consecutiveIdleTrigger=10
receiveTimeout=1000ms
```

项目没有覆盖这些触发参数。负载连续活跃后container请求增加consumer；60秒异常是扩容任务未获得executor线程的结果，不是通过调大timeout应该掩盖的问题。

## 4. Before运行证据

`FORMAL-CALIBRATION-R50-ISOLATED-20260826`：

- order Outbox/result publish约50.2/s。
- measurement期间Rabbit ready从1044增长到5565，OLS约+50.31 msg/s，最大5565；unacked最大23。
- resource日志在17:24:55和17:25:55各出现一次60秒consumer startup timeout。
- Rabbit管理API持续只显示2个result consumers。
- 线程dump只存在`order-result-consumer-1/2`，两者运行在`BlockingQueueConsumer.nextMessage -> AsyncMessageProcessingConsumer`长循环中；没有第3/4个result线程。
- 6025条结果消息发布时间覆盖约119秒，结果消费提交延伸到约138秒，停止输入后才排空。

这证明持续输入期间publish大于有效consume/commit能力。最终queue排空、数量和库存不变量正确，只能证明最终收敛，不能作为持续稳定容量证据。

## 5. 最小修复

修改：

- listener executor新增显式`queue-capacity=0`配置；result和同样采用动态consumer的state executor均使用零队列，dead-letter固定单consumer同样不排队。
- 保留result consumer core=2、max=4、prefetch=10和Hikari=10，没有扩大max consumer。
- 新增result consume/commit/transaction timer与executor active/pool/queue、configured/max gauges。
- benchmark采集器新增order result publish、result queue ready/unacked、consume/commit、Rabbit consumer数及executor指标；完整链路drain必须等待result commit、request/deduction终态和result queue清空。

没有修改Outbox、Confirm、业务事务、SQL状态条件、Hikari、消息格式或错误处理语义。

## 6. 修改文件

生产代码、配置、测试与文档：

- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/config/ResourceRabbitListenerConfig.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/mq/consumer/OrderResultConsumer.java`
- `floworder-server/floworder-resource-service/src/main/java/com/javaup/resource/mq/metrics/OrderResultListenerMetrics.java`
- `floworder-server/floworder-resource-service/src/main/resources/application.yaml`
- `floworder-server/floworder-resource-service/src/test/java/com/javaup/resource/config/ResourceRabbitListenerConfigTest.java`
- `docs/order-result-listener-capacity-analysis.md`

未提交的benchmark资产：

- `benchmark/scripts/run-outbox-capacity.ps1`
- `benchmark/scripts/export-aligned-rate-series.ps1`
- `benchmark/scripts/summarize-db-hikari-run.ps1`
- `benchmark/reports/order-result-listener-after-report.md`
- 对应raw run目录

## 7. 自动化正确性测试

执行：

```text
mvn -pl floworder-server/floworder-resource-service -am
  -Dtest=ResourceRabbitListenerConfigTest,OrderResultMessageServiceIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：4 tests，0 failures，0 errors。

- 4个长生命周期任务能够同时启动，pool扩到4、active=4、queue=0。
- 成功结果保持quota/locked stock并将deduct/request标记为订单已创建。
- 失败结果只释放一次quota和库存。
- 重复结果只保留1条消费日志，不产生重复副作用。
- 非法终态处理事务回滚，消费日志不被错误提交。

## 8. After 10/40/50 req/s

统一条件：10000用户、单热门库存项、carrier threads=100、Hikari=10、Outbox P2（2/4/6）、5秒窗口。

| Target | HTTP completed | DB ACCEPTED | order create | result publish | result consume | result commit | result ready Max/Slope | consumer/executor | 判定 |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| 10/s smoke | 10.29/s | 10.33/s | 10.33/s | 10.11/s | 10.11/s | 10.33/s | 0 / 0 | max 4，queue 0 | 通过 |
| 40/s | 40.09/s | 40.03/s | 40.03/s | 40.17/s | 40.17/s | 40.03/s | 0 / 0 | active 4，queue 0 | 通过 |
| 50/s | 49.98/s | 49.78/s | 49.78/s | 50.21/s | 50.21/s | 49.78/s | 0 / 0 | active 4，queue 0 | 通过 |

50/s资源证据：

- HTTP error=0，P95/P99=17/22ms。
- result unacked avg/max=1.02/3。
- result事务平均/最大=4.80/162.8ms。
- system CPU avg/max=31.6%/70%。
- resource Hikari active avg/max=0.96/5，pending=0。
- MySQL row lock waits约37.53/s，平均3.56ms。
- resource/order Outbox backlog OLS约-0.127/+0.030 msg/s，停止输入后完整终态drain=0.854s。
- 没有consumer startup timeout、executor starvation或服务ERROR。

40/s曾观察到2次既存`ReservationRequestMapper.recoverExpired` deadlock，但没有影响result publish/consume/commit对齐和最终正确性；50/s本轮没有该错误。该scanner锁竞争不属于本次listener根因，也未在本阶段修改。

## 9. 最终判定与边界

50 req/s满足本阶段完整链路闸门：

```text
HTTP -> ACCEPTED -> resource Outbox -> order create
-> order Outbox -> result queue -> resource result transaction
-> request/deduct最终状态回写
```

因此正式Benchmark仍可使用20/40/50 req/s，50/s可以称为“完整订单结果回写链路最高已验证正式target”。这仍是当前单机、10000用户、单热门库存项、90秒measurement的一轮容量证据，不代表生产SLO，也不宣称exactly-once。
