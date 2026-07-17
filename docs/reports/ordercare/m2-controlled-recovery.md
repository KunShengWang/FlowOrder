# OrderCare M2 受控恢复证据

> 本文保留 M2 阶段证据。M3 Action 租约和 reconciliation 现已完成，当前状态见 [M3 Action 对账报告](m3-action-reconciliation.md)。

## 结论

FlowOrder 已完成 OrderCare M2 业务侧纵向闭环：案例事实聚合、不可变 Proposal、版本化人工审批、幂等恢复动作和执行后业务收敛回查已经连成一条真实链路。

这一阶段达到 **Resume Ready**，可以表述为“实现异常订单诊断与人工审批恢复闭环”。它还不包含写请求 UNKNOWN 自动对账、执行租约和进程重启恢复，因此暂不表述为生产级故障恢复系统。

## 权威契约

内部接口默认由 `floworder.admin.enabled=false` 关闭，联调时临时开启：

```text
GET  /internal/recovery/cases/inspect?requestId={requestId}
POST /internal/recovery/proposals
GET  /internal/recovery/proposals/{proposalId}
POST /internal/recovery/proposals/{proposalId}/execute
```

FlowOrder 是 Proposal 与 Recovery Action 的唯一业务事实源。enterprise-agent 生成稳定的 `proposalRequestId` 用于预演请求幂等，但 Proposal 的状态、目标绑定、有效期、指纹、执行动作和业务结果均由 FlowOrder 持久化并裁决。

## 关键设计

### Proposal 与 Action 分离

```text
proposalId      -> 人工审批的不可变预演对象
actionRequestId -> 具有副作用的恢复命令幂等键
```

二者在 Proposal 创建时建立一对一绑定。重复创建使用相同 `proposalRequestId` 返回同一 Proposal；重复执行同一 Proposal 复用同一个 `actionRequestId`。

### 审批绑定具体预演

Proposal 固化以下审批证据：

```text
proposalVersion
stateFingerprint
effectsDigest
warningsDigest
expiresAt
```

execute 不接受客户端重新解释业务事实，而是重新读取权威 Proposal，并校验版本、摘要、有效期与当前业务状态。Proposal 过期或状态漂移后，旧审批不能继续执行。

### 三类状态严格分离

```json
{
  "proposalStatus": "APPROVED",
  "actionStatus": "SUBMITTED",
  "caseOutcome": "RESOLVED"
}
```

- `proposalStatus` 表示预演和审批生命周期；
- `actionStatus` 表示恢复命令是否已提交；
- `caseOutcome` 表示订单、扣减、库存和相关死信是否真正收敛。

因此接口返回成功和 `SUBMITTED` 都不能直接等价为业务恢复成功。

## 自动化证据

真实环境使用 MySQL、Nacos、RabbitMQ 和运行中的 FlowOrder 服务，执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ordercare/m2-controlled-recovery.ps1 -Action E2E -DbPassword 1234
```

结果：`RecoveryProposalHttpE2ETest` 1/1 通过。测试先读取 M1 案例并确认 `REPLAY_CANDIDATE`，再创建 Proposal、执行恢复、等待 MQ 消费，最终验证：

```text
proposalStatus = APPROVED
actionStatus   = SUBMITTED
caseOutcome    = RESOLVED
deductStatus   = RELEASED
inventoryInvariantOk = true
相关死信均进入终态
```

恢复域组合回归命令：

```powershell
mvn -q -pl floworder-server/floworder-resource-service -am -DskipTests=false "-Dtest=MqDeadLetterServiceTest,RecoveryServiceImplTest,DeadLetterRecoveryBaselineIntegrationTest,RecoveryCaseServiceImplTest,RecoveryProposalServiceImplTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

相关测试合计 33 条，失败 0、错误 0、跳过 0，其中：

| 测试类 | 数量 |
| --- | ---: |
| `DeadLetterRecoveryBaselineIntegrationTest` | 6 |
| `MqDeadLetterServiceTest` | 8 |
| `RecoveryProposalHttpE2ETest` | 1 |
| `RecoveryProposalServiceImplTest` | 8 |
| `RecoveryCaseServiceImplTest` | 9 |
| `RecoveryServiceImplTest` | 1 |

## 可重复环境与清理

- 建表迁移：`scripts/ordercare/sql/m2-recovery-proposal-migration.sql`
- E2E 与状态核对：`scripts/ordercare/m2-controlled-recovery.ps1`
- 测试数据清理：`scripts/ordercare/sql/m2-cleanup.sql`

真实联调同时验证了两个工程边界：恢复接口必须在 order-service 启动时显式开启；RabbitMQ 中遗留的 unacked 测试消息会阻塞业务收敛，排查时需要同时核对 ready、unacked 和消费者状态，不能只看 HTTP 返回值。

## 当前边界

M2 尚未覆盖：

- execute 响应丢失后的 UNKNOWN 自动对账；
- EXECUTING 租约和多实例抢占；
- enterprise-agent 进程重启后的自动恢复；
- 重复 resume、崩溃窗口和对账任务证据；
- 生产身份认证、mTLS、灰度和完整指标大盘。

这些是 M3 Interview Strong 的工作范围，不属于当前已完成声明。
