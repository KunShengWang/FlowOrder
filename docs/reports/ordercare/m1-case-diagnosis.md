# OrderCare M1：FlowOrder 只读案例诊断契约

> 日期：2026-07-17
> 状态：`PASSED`
> 契约：`floworder-recovery-case-v1`

## 1. 验收结论

FlowOrder 已提供稳定的只读案例聚合接口：

```http
GET /internal/recovery/cases/inspect?identifierType=REQUEST_ID&identifierValue=...
```

接口支持 `REQUEST_ID`、`ORDER_NO`、`DEDUCT_NO` 和 `DEAD_LETTER_ID` 四类定位方式，聚合预约、订单、扣减、库存、关联死信和恢复动作事实。它不返回数据库 Entity、死信原文、任意 URL 或可执行指令。

交易规则由 FlowOrder 确定性代码判断；Agent 只能解释 `diagnosisCode`、证据、硬风险和服务端候选动作，不能覆盖领域结论。

## 2. 七类确定性诊断

| diagnosisCode | 领域语义 | Agent 可做的事 |
|---|---|---|
| `ALREADY_CONVERGED` | 业务已收敛，无需恢复 | 解释证据，禁止建议重复动作 |
| `REPLAY_CANDIDATE` | 存在满足规则的待处理死信 | 展示服务端候选，建议进入预演 |
| `ACTION_IN_PROGRESS` | 已有恢复命令处理中或已提交 | 提示等待或对账，禁止重复提交 |
| `DEPENDENCY_UNAVAILABLE` | 订单服务等权威依赖不可用 | 明确事实不完整，稍后重试或转人工 |
| `FACT_CONFLICT` | 库存不变量或跨域事实冲突 | 停止自动恢复，升级人工排查 |
| `UNSUPPORTED_EVENT` | 当前消息类型不支持自动恢复 | 解释边界，转人工 |
| `NO_RECOVERY_EVIDENCE` | 没有足够恢复证据 | 禁止模型猜测或构造动作 |

特别验证了“资源服务本地订单状态滞后”场景：当远端订单已 `TIMEOUT`，本地仍为 `RESERVED`，且存在匹配的未解决 `ORDER_TIMEOUT` 死信时，该差异属于死信导致的可解释证据，不能误判为 `FACT_CONFLICT`。

## 3. 自动化证据

运行确定性分支测试：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ordercare/m1-case-diagnosis.ps1 -Action Verify
```

2026-07-17 实测结果：

```text
RecoveryCaseServiceImplTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖七类诊断、“状态差由关联死信解释”的关键分支，以及面向 Agent 契约的错误文本 512 字符边界。

## 4. 真实 HTTP 聚合 E2E

运行条件：MySQL、Redis、Nacos 和 order-service 已启动。resource-service 由 Spring Boot 测试在随机端口启动，Rabbit Listener 在本测试中关闭，避免修改夹具状态。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ordercare/m1-case-diagnosis.ps1 `
  -Action E2E `
  -DbPassword $env:FLOWORDER_MYSQL_PASSWORD
```

该脚本严格执行：

```text
清理旧夹具
-> 注入真实 MySQL 异常案例
-> 启动最新 resource-service 测试上下文
-> 通过真实 HTTP Controller 调用 inspect
-> 经 Nacos/Feign 查询真实 order-service
-> 断言完整案例契约
-> finally 清理固定夹具
```

2026-07-17 实测结果：

```text
RecoveryCaseHttpE2ETest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

schemaVersion       = floworder-recovery-case-v1
diagnosisCode       = REPLAY_CANDIDATE
factsComplete       = true
recoveryEligible    = true
deadLetter.status   = PENDING
candidate.owner     = FLOWORDER
evidence            = ORDER_STATUS_GAP_EXPLAINED_BY_DEAD_LETTER
```

## 5. 责任边界

- `recoveryEligible` 和候选动作由 FlowOrder 返回，不由模型推断。
- 候选动作只是只读诊断结果，不等于 Proposal，更不等于执行授权。
- M1 没有写工具，不能宣称已经预演、审批、重放或恢复订单。
- M2 才会增加 Proposal、HITL、execute 和确定性收敛验证。
- 当前内部接口仅通过 `floworder.admin.enabled=true` 开启，服务认证属于 M4。
