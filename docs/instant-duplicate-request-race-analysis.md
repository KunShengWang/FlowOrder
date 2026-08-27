# Instant 并发重复 requestId 准入凭证竞态分析

## 1. 失败现象

代码基线 `90377aa31fc4720294171cfd92dc38daf90f550d` 的重复请求专项使用1000个唯一 requestId，每个并发提交5次，共5000次HTTP请求。Redis形成1000次首次准入和4000次重复命中，MySQL中1000个预约请求都进入了处理阶段，但最终只有643个形成 acceptance、deduction 和 order，另外357个请求在两次重试后进入人工审核。

357条失败日志的根异常均为“Redis准入凭证不存在或已释放”。原始 JTL、服务日志、聚合CSV和逐 requestId 失败证据保存在 `benchmark/raw/DUPLICATE-REQUEST-R1-20260827/` 与 `benchmark/results/duplicate-request-failure-evidence.csv`，状态标记为 `PRE_FIX_FAIL`。

## 2. 四种组合的业务语义

| Redis结果 | submission.created | 语义 | credential处理 |
|---|---:|---|---|
| ADMITTED_NEW | true | 当前调用完成Redis首次准入并插入预约请求 | 保留并标记已持久化 |
| ADMITTED_NEW | false | 另一并发重复调用已经先插入同一合法预约请求 | 保留并复用现有请求 |
| ADMITTED_DUPLICATE | true | 首次准入调用尚未落库，重复调用先插入预约请求 | 保留并标记已持久化 |
| ADMITTED_DUPLICATE | false | 预约请求已经存在的正常幂等重放 | 保留并复用现有请求 |

`created=false` 只说明当前线程不是数据库行的插入者，不能证明业务事实不存在，也不能证明Redis库存属于“多扣的一份”。credential以 requestId 和 digest 表示业务准入事实，不是某个HTTP线程的私有资源。

## 3. 竞态时序与根因

```text
线程A                         线程B
ADMITTED_NEW
暂停
                              ADMITTED_DUPLICATE
                              insert reservation_request
                              created=true
恢复
查询到同一requestId
created=false
旧代码 release credential  ---> 已持久化请求失去准入凭证
                              claim/worker校验credential失败
                              RETRY -> MANUAL_REVIEW
```

旧实现把 `ADMITTED_NEW && created=false` 当成释放充分条件。这个判断混淆了“当前线程没有插入行”和“数据库不存在合法业务事实”，导致首次准入线程释放了并发重复线程已经持久化并准备处理的共享credential。

## 4. 最小修复

删除 `ADMITTED_NEW && submission.created=false -> release` 分支。只要 `submitInstant()` 返回同 requestId、同业务参数的合法预约请求，就保留credential、标记请求已持久化，并继续复用现有状态；处理权仍由原有数据库 claim CAS 仲裁。

本次不增加Redis锁或分布式锁，不串行化submit，不修改Lua、库存条件更新、Outbox、RabbitMQ、Hikari、listener并发或生产线程池。

数据库明确返回同 requestId 不同业务参数时仍判定冲突。Redis Lua首先使用digest拒绝不同payload；数据库兜底冲突路径释放的只是当前冲突digest对应的新准入，并由Lua digest比较提供fencing，不会删除不同digest的合法credential。

## 5. 自动化回归

- 确定性竞态：使用 CountDownLatch 固定“A先获得 ADMITTED_NEW、B先插入请求、A后读到 created=false”的顺序，验证不调用release且只执行一次processor。
- 同payload并发幂等：2线程、5线程和64线程分别验证只创建一份模拟请求事实、claim只有一个winner、processor只执行一次且credential不释放。
- 原有冲突测试继续验证同requestId不同参数被拒绝；Redis与数据库冲突路径均不复用不同payload。
- benchmark checker新增业务丢失判定：expected=1000、acceptance/deduction/order=643、manual review=357必须FAIL。
- 原oversell/inventory negative control继续必须FAIL。

## 6. Before / After

### PRE_FIX

```text
HTTP=5000, unique requestId=1000
Redis new/duplicate=1000/4000
M=1000
acceptance/deduction/order=643/643/643
manual review=357
结果=PRE_FIX_FAIL
```

### AFTER_FIX

自动化回归已经证明确定性竞态不再释放credential，2/5/64线程下只允许一个处理者。真实5000:100、1000×5五轮与有序digest conflict三轮结果在新代码commit后执行，并记录到 `benchmark/reports/stock-correctness-after-fix-report.md`；完成前不冻结简历数据。
