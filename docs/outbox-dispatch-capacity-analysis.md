# FlowOrder Outbox Dispatch 容量分析

> 基线：`0f2cf8a`
> 有界并发与 fencing：`79c4c4f`
> 本阶段目标：只修正 scanner/dispatcher 的供给节奏，不改变 Outbox 状态机、Confirm 模型、事务和消费协议。

## 1. P2 失败复盘

`79c4c4f` 的 50 并发 Instant P2 使用：2 workers、queue capacity 4、max-in-flight 6、scan delay 200 ms。

HTTP 达到 368.35 QPS、P95 336 ms，但 fulfillment 只有约 25.8/s、P95 343 s；持续输入时 backlog 正增长，停止输入后约 401 s 才排空。最终 11018 request = 11018 deduction = 11018 order，重复订单、重复扣减、负库存和库存恒等式违例均为 0。因此这是容量失败，不是正确性失败。

## 2. scanner 真实源码模型

resource 的 `MqOutboxPublishTask.publish()` 与 order 的 `OrderResultOutboxPublishTask.publish()` 完全同构：

1. `@Scheduled(fixedDelayString = scan-delay-ms)` 使用各自单线程 `ThreadPoolTaskScheduler`。
2. 每次 invocation 先回收过期租约，再读取 `availablePermits`。
3. `queryLimit = min(batchSize, availablePermits)`，先取得这些 permit，再查询、逐条 claim、submit executor。
4. `publish()` 只负责 submit，不等待 worker 内的同步 Confirm，提交完当前批次就返回。
5. Spring fixed-delay 的下一轮起点是本轮方法返回后再等待 200 ms。
6. worker 的 `finally` 只执行 `inFlightPermits.release()`，没有唤醒、续扫或立即调度逻辑。

因此，worker 即使在约 12 ms 内完成，也必须等待下一次 scheduled invocation 才能取得下一批消息。当前没有实现“有 backlog 且有 permit 就立即继续 dispatch”。

## 3. 当前模型的理论 dispatch ceiling

忽略 SQL、CAS 和提交耗时，固定 200 ms cadence 每秒最多 5 轮：

| 档位 | max-in-flight | 理论 dispatch ceiling |
|---|---:|---:|
| P2 | 6 | 5 × 6 = 30/s |
| P4 | 12 | 5 × 12 = 60/s |
| P8 | 24 | 5 × 24 = 120/s |

P2 实测 fulfillment 约 25.8/s，与扣除 SQL、claim、executor submit 和调度抖动后的 30/s ceiling 接近。

## 4. worker 利用率证据

- resource/order Confirm 平均约 12.3/12.7 ms。
- claim→worker start Max 约 72.5/104.5 ms，远小于 60 s lease。
- executor rejected=0，stale worker=0。
- 两个 worker 若只受 12 ms Confirm 限制，粗略服务能力远高于 30/s；实际吞吐却贴近 `5 scans/s × 6`。

这些证据支持“worker 没有真正吃满、固定 scanner cadence 是当前主瓶颈”。它仍是需要 After 实测验证的容量假设，而不是生产容量结论。

## 5. A1 / A2 最小方案比较

| 维度 | A1 快速续扫 polling | A2 work-conserving dispatcher |
|---|---|---|
| 实现 | backlog 时 5～10 ms 再扫，无任务时 200 ms | 单 dispatcher 有 permit 就补任务；无 permit 阻塞等待 worker release；空扫描后才返回 idle backoff |
| 改动规模 | 小 | 小到中等，只改两侧 task/metrics/tests |
| CPU 空转 | 低但仍固定高频 wake-up | 低；permit 等待是阻塞等待 |
| DB 空查询 | backlog 时可能每 5～10 ms 查询 | 只在取得 permit 后查询；空结果立即退出 |
| shutdown | 依赖 polling 周期退出 | 带运行标志，permit 等待使用有限超时检查 shutdown |
| 多实例 | DB claim CAS 仲裁，安全 | DB claim CAS 仲裁，安全 |
| Spring `@Scheduled` | 动态 delay 表达不自然，通常需要自调度 | 保留 fixedDelay；一次 invocation 在有工作时持续供给，返回后 fixedDelay 仅作为 idle backoff |
| 测试性 | 需要验证快速/空闲两种 delay | 可直接验证同一次 `publish()` 连续补批、空扫描退出、permit 顺序 |
| busy spin | 需要严格限制最小 delay | 无；无 permit 时阻塞，无候选时退出 |
| claim/lease/token | 不变 | 不变 |

## 6. 推荐方案

选择 A2，但保持实现最小：

- `@Scheduled(fixedDelay=200ms)` 保留，含义改为无候选任务后的 idle backoff。
- scheduled invocation 内使用单 dispatcher 循环。
- dispatcher 先阻塞等待至少一个 permit，再一次性补取本轮其余 available permit，仍严格执行 permit → query → claim → submit。
- 有候选任务时立即进入下一轮；permit 用尽时阻塞等待 worker release，release 后立即续扫。
- 空查询时释放预留 permit、记录 empty scan 并返回；随后才发生 200 ms fixedDelay。
- permit 等待使用 200 ms 有限超时，只检查 shutdown，不访问 DB，不形成 busy spin。
- 租约回收仍按约 200 ms 节流执行，避免 work-conserving 循环放大 reclaim 查询。

保留数据库 claim CAS、claim owner/token fencing、60 s lease、有界 executor/max-in-flight、worker 内同步 Confirm、原重试状态机和 at-least-once 语义。

## 7. 修改文件

- resource/order 两侧 Outbox publish task：work-conserving dispatch loop、有限 permit 等待与 shutdown 边界。
- resource/order 两侧 Outbox metrics：active worker、queue size、available permit、dispatch、empty scan。
- resource/order task tests：同一次 invocation 连续补批、worker 释放 permit 后立即续扫、原 token/reject 行为。
- 本阶段没有修改 `application.yaml`、Hikari、executor 档位或 MQ/Consumer 协议；`scan-delay-ms=200` 继续保留，语义变为 idle backoff。
- 未跟踪的 `benchmark/scripts/run-outbox-capacity.ps1` 与 `restart-outbox-profile.ps1` 补齐失败路径 runtime series、executor/permit、内存和速率采集；它们属于 benchmark 资产，不进入业务 commit。

## 8. 自动化测试

- resource MQ/Outbox 定向回归：35 tests，0 failure。
- order MQ/Outbox/状态机定向回归：17 tests，0 failure。
- 合计：52 tests，0 failure。
- 新增验证包括：同一次 scheduled invocation 连续补批；max-in-flight=1 时 dispatcher 阻塞等待异步 worker，worker release permit 后无需等待下一次 200 ms tick 即继续派发。
- Maven package：停止 Windows 下占用 JAR 的旧服务进程后 `BUILD SUCCESS`。第一次 repackage 失败仅因旧 Java 进程锁定目标 JAR，不是编译或测试失败。

## 9. 新容量闸门

- correctness、重复副作用、库存不变量、持续 executor reject、不可控 CPU/DB/JMeter 饱和、严重 Hikari 等待或服务异常：立即停止。
- 若正确性通过且系统健康，仅当前档位吞吐不足：记录该档容量失败，允许 P2 → P4 → P8 继续寻找稳定档位。
- 通过仍要求输入期 backlog 无长期明显正斜率，停止输入后合理时间排空，fulfillment QPS 能支撑实际稳定 admission rate。

## 10. After P2/P4/P8 结果

### 10.1 10 并发 smoke

- Run：`OUTBOX-DISPATCH-SMOKE-20260826-104057-INSTANT-P2`。
- 10/10 HTTP 成功，HTTP P95 595 ms。
- 10 request = 10 deduction = 10 order。
- duplicate order=0，duplicate deduction=0，inventory diff=0，negative stock=0。
- fulfillment 秒级采样 P95=1 s，正确性 smoke 通过。

### 10.2 50 并发 P2

Run：`OUTBOX-DISPATCH-AFTER-20260826-P2-T50`。保持 2 workers、queue capacity 4、max-in-flight 6、Hikari 不变；50 threads、30 s、ramp-up 5 s。

| 维度 | 结果 |
|---|---|
| HTTP | 7730/7730 成功，0 error；256.33 QPS；Avg/P50/P95/P99/Max = 175.48/122/463/666/1988 ms |
| 最终收敛 | 7730 request = 7730 deduction = 7730 order；drain 79.155 s |
| fulfillment | P50/P95/P99/Max = 36/44/45/45 s |
| 输入期平均速率 | admission 127.94/s；fulfillment 23.08/s；resource publish 23.20/s；order publish 21.58/s |
| drain 期平均速率 | fulfillment 86.53/s；resource publish 86.45/s；order publish 87.89/s |
| Confirm | resource 7730 次，Avg/Max 17.37/172 ms；order 7730 次，Avg/Max 20.86/159 ms |
| claim→worker | resource Avg/Max 29.17/684.79 ms；order Avg/Max 19.34/329.30 ms，远低于 60 s lease |
| executor | resource active Avg/Max 1.60/2，queue Avg/Max 1.25/4，permit Min/Max 0/6；reject resource/order 均为 0 |
| CPU | 输入期 system CPU Avg 91.9%，Max 100%；20 个样本中 13 个不低于 95% |
| Hikari | resource active Avg/Max 8.55/10；pending Avg/Max 37.6/52；20 个样本中 16 个 pending 不低于 20 |
| GC | resource 86 次/0.190 s；order 40 次/0.079 s |
| RabbitMQ | ready Max 478；unacked Max 21 |
| 正确性 | duplicate order=0；duplicate deduction=0；negative stock=0；inventory diff=0 |

输入期 resource backlog 从 0 增至 3347，停止输入后最终排空。与 `79c4c4f` P2 的约 25.8/s、401 s drain 相比，新 dispatcher 在 drain 期约 86/s、79.155 s 排空，说明固定 `5 scans/s × 6` cadence ceiling 已被解除。

但是，本档不能判为稳定容量通过：同机输入阶段 CPU 持续接近饱和，resource Hikari pending 持续严重，且 admission 高于 fulfillment，backlog 仍明显正增长。这命中新闸门的“CPU/DB 不可控饱和或 Hikari 持续严重等待”健康 STOP 条件，而不只是低 worker 档位容量不足。

### 10.3 P4/P8

P4、P8 未执行。P2 正确性通过且 scanner 修正有效，但系统健康闸门失败，按规则停止后续档位；未调整 Hikari，也未通过扩大线程池掩盖瓶颈。

结论边界：本次数据证明 work-conserving dispatcher 消除了固定 tick 的调度上限，并保持现有并发正确性；它没有证明 50 并发稳定容量已经通过，更不是生产容量或 exactly-once 证据。
