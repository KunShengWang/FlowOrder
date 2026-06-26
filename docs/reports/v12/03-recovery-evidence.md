# V12 恢复控制面证据摘要

## 1. V10 能力

V10-core 已实现：

```text
POST /internal/recovery/dead-letter/preview
POST /internal/recovery/dead-letter/execute
GET  /internal/recovery/reservation/check?requestId=xxx
```

接口受以下配置保护：

```yaml
floworder:
  admin:
    enabled: true
```

默认建议保持 `false`，只在本地验证或受控环境临时开启。

## 2. 已验证 preview

已验证请求：

```json
{
  "actionRequestId": "v10-preview-001",
  "deadLetterId": 2070417281797263362,
  "actionType": "REPLAY",
  "operator": "codex",
  "reason": "v10 preview verify"
}
```

结果：

| 字段 | 结果 |
| --- | --- |
| `canExecute` | `true` |
| `currentStatus` | `0` |
| `recommendedAction` | `REPLAY` |
| `fo_recovery_action_log.status` | `0 PREVIEWED` |
| `execute_result` | `NULL` |
| `last_error` | `NULL` |

结论：恢复动作可以先预览并写审计日志，不会直接修改业务状态。

## 3. 当前复验状态

本轮 V12 复验时，当前运行配置为：

```yaml
floworder.admin.enabled=false
```

因此 `/internal/recovery/**` 返回 404，符合“恢复接口默认不暴露”的安全边界。

如需复验 V10 接口，需要临时开启 `floworder.admin.enabled=true` 并重启 `resource-service`。

## 4. 可用于简历的结论

```text
补充最小恢复控制面，对 DLQ 死信恢复采用 preview -> execute 两阶段操作，execute 使用 actionRequestId 保证幂等，并通过恢复审计表记录 operator、reason、预览结果和执行结果，避免直接裸调恢复接口。
```
