# OrderCare M3 Action 租约与对账证据

## 结论

FlowOrder 已完成 OrderCare M3 业务侧故障正确性：Recovery Action 具备执行租约、原 `actionRequestId` 接管、权威查询和确定性 reconciliation，能够支撑 enterprise-agent 在 execute 响应丢失或进程崩溃后恢复，而不创建第二个副作用命令。

该结论达到 Interview Strong 的业务侧要求，但不等于生产级多节点容灾。可信服务身份、网络分区、容量 SLO 和告警仍属于 M4。

## 数据模型

`fo_recovery_action_log` 新增：

```text
execution_owner
execution_lease_until
last_heartbeat_at
reconcile_count
reconciled_at
```

迁移脚本：

```text
scripts/ordercare/sql/m3-recovery-action-lease-migration.sql
```

Action 与业务结果保持分离：

```text
actionStatus = NOT_STARTED / PREVIEWED / EXECUTING / SUBMITTED / FAILED / MANUAL_REVIEW
caseOutcome  = ALREADY_CONVERGED / RESOLVED / NOT_CONVERGED / MANUAL_REVIEW
```

## 接口

恢复管理接口仍由 `floworder.admin.enabled=false` 默认关闭：

```text
GET  /internal/recovery/actions/{actionRequestId}
POST /internal/recovery/actions/{actionRequestId}/reconcile
```

reconcile 只接受稳定的 `executionOwner`，目标、动作类型和 actionRequestId 均从 FlowOrder 权威记录恢复。

## 确定性决策

| Action/业务事实 | 决策 |
| --- | --- |
| `SUBMITTED` 且业务收敛 | `RESOLVED`，不补发 |
| `SUBMITTED` 但未收敛 | `WAITING_CONVERGENCE`，不补发 |
| `EXECUTING` 且租约有效 | `WAITING_ACTIVE_LEASE` |
| `EXECUTING`、租约过期、死信 `PENDING` | 使用原 `actionRequestId` CAS 接管 |
| `EXECUTING`、死信 `REPLAYING` | `WAITING_REPLAY_RESULT` |
| 业务已收敛但 Action 仍 `EXECUTING` | 补记 `SUBMITTED` 与 reconciledAt |
| 无法证明执行或结果 | `MANUAL_REVIEW` |

新 owner 不允许绕过活跃租约；过期接管也不会生成新的 Action 行或新的业务幂等键。

## 自动化证据

窄回归：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/ordercare/m3-fault-recovery.ps1 -Action Verify
```

结果：

```text
RecoveryActionLeaseServiceTests          3/3
RecoveryActionReconciliationServiceTests 5/5
RecoveryProposalServiceImplTest          8/8
RecoveryServiceImplTest                  1/1
合计                                     17/17
```

覆盖场景：

- 活跃租约拒绝并发第二执行者；
- 过期租约使用原 actionRequestId 接管；
- 响应已丢失但业务收敛时补记 Action；
- REPLAYING 等待而不是再次重放；
- 无法证明时进入 MANUAL_REVIEW。

恢复域完整报告：

```text
42 tests
failures = 0
errors   = 0
skipped  = 0
```

## 真实 MySQL/RabbitMQ E2E

执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/ordercare/m3-fault-recovery.ps1 `
  -Action E2E -DbPassword $env:FLOWORDER_MYSQL_PASSWORD
```

`RecoveryProposalHttpE2ETest` 在真实 MySQL、Nacos、RabbitMQ 和运行中的 order-service 下完成：

```text
inspect REPLAY_CANDIDATE
-> create immutable Proposal
-> execute original Action
-> consume ORDER_TIMEOUT message
-> deduct RELEASED / inventory invariant true / dead letter resolved
-> GET Action = SUBMITTED + RESOLVED
-> repeated reconcile = same actionRequestId + RESOLVED
```

测试 1/1 通过，并在结束后清理固定异常夹具。

## 面试表述

可以说：

> FlowOrder 将 Recovery Action 建模为带执行租约的领域命令；写结果未知时，调用方只能查询或 reconcile 原 actionRequestId。活跃租约防并发重复执行，过期租约只在死信仍 PENDING 时允许 CAS 接管，业务收敛和命令提交状态独立判断。

不能说：

- 已完成跨机房容灾或网络分区一致性验证；
- 本地一个 Spring E2E 等于生产多实例证据；
- `SUBMITTED` 就等于库存一定恢复；
- admin 内部接口已经具备生产鉴权。
