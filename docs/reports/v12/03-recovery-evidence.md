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

## 4. execute 幂等修复与自动化验证

本轮补充 execute 幂等验证时发现一个小范围正确性缺口：

```text
旧逻辑：execute -> 重新 preview 当前死信状态 -> 再检查 actionRequestId
风险：第一次 execute 成功后，死信状态已经变成 IGNORED/REPLAYING/RESOLVED；
     第二次相同 actionRequestId 可能在 preview 阶段被拦截，无法返回幂等成功。
```

修复策略：

```text
execute 入口先根据 actionRequestId 查询恢复审计日志；
如果同一个 actionRequestId、同一个目标、同一个动作已经 SUCCEEDED，
直接返回 IDEMPOTENT_SUCCEEDED；
只有不是已成功动作时，才重新 preview 当前死信状态。
```

新增测试：

```text
floworder-server/floworder-resource-service/src/test/java/com/javaup/resource/service/RecoveryServiceImplTest.java
```

验证命令：

```bash
mvn -pl floworder-server/floworder-resource-service -am "-Dtest=RecoveryServiceImplTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

验证结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖断言：

| 验证点 | 结果 |
| --- | --- |
| 第一次 execute | 返回 `SUCCEEDED` |
| 第二次相同 `actionRequestId` execute | 返回 `IDEMPOTENT_SUCCEEDED` |
| 恢复动作执行次数 | `deadLetterService.ignore(...)` 只调用 1 次 |
| 第二次是否重新读取死信状态 | 不重新读取，直接基于 actionLog 幂等返回 |

结论：V10 execute 的 `actionRequestId` 幂等已经由代码路径和自动化测试共同验证。

## 5. 可用于简历的结论

```text
补充最小恢复控制面，对 DLQ 死信恢复采用 preview -> execute 两阶段操作，execute 使用 actionRequestId 保证幂等，并通过恢复审计表记录 operator、reason、预览结果和执行结果，避免直接裸调恢复接口。
```
