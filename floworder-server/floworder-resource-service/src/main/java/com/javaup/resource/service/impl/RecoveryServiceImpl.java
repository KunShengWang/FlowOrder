package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.dto.ReservationRequestResultDto;
import com.javaup.exception.BizException;
import com.javaup.resource.dto.RecoveryDeadLetterRequest;
import com.javaup.resource.dto.RecoveryExecuteResult;
import com.javaup.resource.dto.RecoveryPreviewResult;
import com.javaup.resource.dto.ReservationRecoveryCheckResult;
import com.javaup.resource.entity.*;
import com.javaup.resource.mapper.*;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.service.RecoveryService;
import com.javaup.resource.service.ReservationRequestService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class RecoveryServiceImpl implements RecoveryService {

    private static final String ACTION_REPLAY = "REPLAY";
    private static final String ACTION_IGNORE = "IGNORE";
    private static final String TARGET_DEAD_LETTER = "DEAD_LETTER";
    private static final String TARGET_RESERVATION = "RESERVATION";

    private static final int ACTION_PREVIEWED = 0;
    private static final int ACTION_EXECUTING = 10;
    /**
     * 恢复命令已经可靠提交，不代表订单、库存和死信已经完成业务收敛。
     */
    private static final int ACTION_SUBMITTED = 20;
    private static final int ACTION_FAILED = 30;
    private static final int ACTION_MANUAL_REVIEW = 40;

    private static final int DEAD_PENDING = 0;
    private static final int DEAD_REPLAYING = 10;
    private static final int DEAD_RESOLVED = 20;
    private static final int DEAD_IGNORED = 30;

    private final MqDeadLetterService deadLetterService;
    private final RecoveryActionLogMapper actionLogMapper;
    private final ReservationRequestService requestService;
    private final ReservationRequestMapper requestMapper;
    private final StockDeductRecordMapper deductRecordMapper;
    private final StockItemMapper stockItemMapper;
    private final UserReservationQuotaMapper quotaMapper;
    private final MqOutboxMapper outboxMapper;
    private final OrderClient orderClient;
    private final ObjectMapper objectMapper;
    private final long actionLeaseSeconds;
    private final Clock clock;

    @Autowired
    public RecoveryServiceImpl(
            MqDeadLetterService deadLetterService,
            RecoveryActionLogMapper actionLogMapper,
            ReservationRequestService requestService,
            ReservationRequestMapper requestMapper,
            StockDeductRecordMapper deductRecordMapper,
            StockItemMapper stockItemMapper,
            UserReservationQuotaMapper quotaMapper,
            MqOutboxMapper outboxMapper,
            OrderClient orderClient,
            ObjectMapper objectMapper,
            @Value("${floworder.recovery.action-lease-seconds:15}") long actionLeaseSeconds
    ) {
        this(deadLetterService, actionLogMapper, requestService, requestMapper,
                deductRecordMapper, stockItemMapper, quotaMapper, outboxMapper,
                orderClient, objectMapper, actionLeaseSeconds, Clock.systemDefaultZone());
    }

    /** 保留给现有单元测试和非 Spring 构造，生产构造由上面的 @Autowired 入口负责。 */
    public RecoveryServiceImpl(
            MqDeadLetterService deadLetterService,
            RecoveryActionLogMapper actionLogMapper,
            ReservationRequestService requestService,
            ReservationRequestMapper requestMapper,
            StockDeductRecordMapper deductRecordMapper,
            StockItemMapper stockItemMapper,
            UserReservationQuotaMapper quotaMapper,
            MqOutboxMapper outboxMapper,
            OrderClient orderClient,
            ObjectMapper objectMapper
    ) {
        this(deadLetterService, actionLogMapper, requestService, requestMapper,
                deductRecordMapper, stockItemMapper, quotaMapper, outboxMapper,
                orderClient, objectMapper, 15, Clock.systemDefaultZone());
    }

    RecoveryServiceImpl(
            MqDeadLetterService deadLetterService,
            RecoveryActionLogMapper actionLogMapper,
            ReservationRequestService requestService,
            ReservationRequestMapper requestMapper,
            StockDeductRecordMapper deductRecordMapper,
            StockItemMapper stockItemMapper,
            UserReservationQuotaMapper quotaMapper,
            MqOutboxMapper outboxMapper,
            OrderClient orderClient,
            ObjectMapper objectMapper,
            long actionLeaseSeconds,
            Clock clock
    ) {
        this.deadLetterService = deadLetterService;
        this.actionLogMapper = actionLogMapper;
        this.requestService = requestService;
        this.requestMapper = requestMapper;
        this.deductRecordMapper = deductRecordMapper;
        this.stockItemMapper = stockItemMapper;
        this.quotaMapper = quotaMapper;
        this.outboxMapper = outboxMapper;
        this.orderClient = orderClient;
        this.objectMapper = objectMapper;
        this.actionLeaseSeconds = Math.max(1, actionLeaseSeconds);
        this.clock = clock;
    }

    @Override
    public RecoveryPreviewResult previewDeadLetter(RecoveryDeadLetterRequest request) {
        validateDeadLetterRequest(request, false);
        RecoveryPreviewResult result = buildDeadLetterPreview(request);
        if (StringUtils.hasText(request.getActionRequestId())) {
            savePreviewLog(request, result);
        }
        return result;
    }

    @Override
    public RecoveryExecuteResult executeDeadLetter(RecoveryDeadLetterRequest request) {
        validateDeadLetterRequest(request, true);
        RecoveryActionLogEntity existing = findActionLog(request.getActionRequestId());
        if (existing != null) {
            ensureSameAction(existing, request);
            if (Objects.equals(existing.getStatus(), ACTION_SUBMITTED)) {
                return idempotentSubmitted(existing);
            }
        }

        RecoveryPreviewResult preview = buildDeadLetterPreview(request);
        if (!Boolean.TRUE.equals(preview.getCanExecute())) {
            throw new BizException("恢复动作当前不可执行：" + String.join(";", preview.getWarnings()));
        }

        RecoveryActionLogEntity log = prepareExecuteLog(request, preview);
        if (Objects.equals(log.getStatus(), ACTION_SUBMITTED)) {
            return idempotentSubmitted(log);
        }

        RecoveryExecuteResult result = buildExecutingResult(request);
        try {
            if (ACTION_REPLAY.equals(request.getActionType())) {
                deadLetterService.replay(request.getDeadLetterId(), request.getOperator());
                result.setMessage("dead letter replay submitted");
            } else if (ACTION_IGNORE.equals(request.getActionType())) {
                deadLetterService.ignore(
                        request.getDeadLetterId(),
                        request.getOperator(),
                        request.getReason(),
                        Boolean.TRUE.equals(request.getForce())
                );
                result.setMessage("dead letter ignored");
            } else {
                throw new BizException("不支持的恢复动作：" + request.getActionType());
            }
            result.setStatus("SUBMITTED");
            result.setExecutedAt(LocalDateTime.now());
            markExecuteSubmitted(log.getId(), log.getExecutionOwner(), result);
            return result;
        } catch (RuntimeException exception) {
            result.setStatus("FAILED");
            result.setMessage(limit(exception.getMessage(), 512));
            result.setExecutedAt(LocalDateTime.now());
            markExecuteFailed(log.getId(), log.getExecutionOwner(), result, exception.getMessage());
            throw exception;
        }
    }

    @Override
    public ReservationRecoveryCheckResult checkReservation(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            throw new BizException("requestId不能为空");
        }

        ReservationRecoveryCheckResult result = new ReservationRecoveryCheckResult();
        result.setRequestId(requestId);

        ReservationRequestResultDto requestResult = requestService.getResult(requestId);
        result.setReservationRequest(requestResult);

        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getRequestId, requestId)
                        .last("limit 1")
        );
        result.setDeductRecord(record);

        if (record != null) {
            result.setStockItem(stockItemMapper.selectById(record.getStockItemId()));
            result.setQuota(quotaMapper.selectOne(
                    Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                            .eq(UserReservationQuotaEntity::getStockItemId, record.getStockItemId())
                            .eq(UserReservationQuotaEntity::getUserId, record.getUserId())
                            .last("limit 1")
            ));
            result.setResourceOutboxes(outboxMapper.selectList(
                    Wrappers.<MqOutboxEntity>lambdaQuery()
                            .eq(MqOutboxEntity::getBizKey, record.getDeductNo())
                            .orderByAsc(MqOutboxEntity::getCreatedAt)
            ));
        } else if (requestResult.getOrderNo() == null) {
            ReservationRequestEntity request = requestMapper.selectOne(
                    Wrappers.<ReservationRequestEntity>lambdaQuery()
                            .eq(ReservationRequestEntity::getRequestId, requestId)
                            .last("limit 1")
            );
            if (request != null) {
                result.setStockItem(stockItemMapper.selectById(request.getStockItemId()));
            }
        }

        fillOrder(result, requestId);
        fillInventoryInvariant(result);
        result.setUnresolvedDeadLetterCount(deadLetterService.countUnresolved());
        fillWarnings(result);
        return result;
    }

    private RecoveryPreviewResult buildDeadLetterPreview(RecoveryDeadLetterRequest request) {
        MqDeadLetterAdminDto dead = deadLetterService.findById(request.getDeadLetterId());
        RecoveryPreviewResult result = new RecoveryPreviewResult();
        result.setActionRequestId(request.getActionRequestId());
        result.setActionType(request.getActionType());
        result.setTargetType(TARGET_DEAD_LETTER);
        result.setTargetKey(String.valueOf(request.getDeadLetterId()));
        result.setCurrentStatus(dead.getStatus());
        result.setDeadLetter(dead);

        if (ACTION_REPLAY.equals(request.getActionType())) {
            previewReplay(dead, result);
        } else if (ACTION_IGNORE.equals(request.getActionType())) {
            previewIgnore(request, dead, result);
        } else {
            result.setCanExecute(false);
            result.setRecommendedAction("UNKNOWN");
            result.getWarnings().add("不支持的动作，只允许 REPLAY 或 IGNORE");
        }
        return result;
    }

    private void previewReplay(MqDeadLetterAdminDto dead, RecoveryPreviewResult result) {
        result.setRecommendedAction(ACTION_REPLAY);
        result.getEffects().add("重新触发原始 Outbox 或通知订单服务重放原消息");
        result.getEffects().add("不会直接修改订单或库存终态，最终由原消费者幂等收敛");
        if (Objects.equals(dead.getStatus(), DEAD_PENDING)) {
            result.setCanExecute(true);
            return;
        }
        result.setCanExecute(false);
        result.getWarnings().add("只有 PENDING 死信允许 replay，当前状态=" + dead.getStatus());
    }

    private void previewIgnore(
            RecoveryDeadLetterRequest request,
            MqDeadLetterAdminDto dead,
            RecoveryPreviewResult result
    ) {
        result.setRecommendedAction(ACTION_IGNORE);
        result.getEffects().add("将死信标记为 IGNORED，并记录 operator/reason");
        result.getEffects().add("不会自动释放库存或修改订单，需要确认业务已收敛");
        boolean statusAllowed =
                Objects.equals(dead.getStatus(), DEAD_PENDING)
                        || Objects.equals(dead.getStatus(), DEAD_REPLAYING);
        result.setCanExecute(statusAllowed);
        if (!statusAllowed) {
            result.getWarnings().add("只有 PENDING/REPLAYING 死信允许 ignore，当前状态=" + dead.getStatus());
        }
        if (!Boolean.TRUE.equals(request.getForce())) {
            result.getWarnings().add("非 force 忽略会由 MqDeadLetterService 再次校验业务是否已收敛");
        } else {
            result.getWarnings().add("force=true 代表人工确认后强制忽略，必须保留明确原因");
        }
    }

    private void validateDeadLetterRequest(RecoveryDeadLetterRequest request, boolean execute) {
        if (request == null
                || request.getDeadLetterId() == null
                || !StringUtils.hasText(request.getActionType())) {
            throw new BizException("恢复请求参数不完整");
        }
        request.setActionType(request.getActionType().trim().toUpperCase());
        if (execute) {
            if (!StringUtils.hasText(request.getActionRequestId())) {
                throw new BizException("execute 必须携带 actionRequestId");
            }
            if (!StringUtils.hasText(request.getOperator())) {
                throw new BizException("execute 必须携带 operator");
            }
            if (ACTION_IGNORE.equals(request.getActionType()) && !StringUtils.hasText(request.getReason())) {
                throw new BizException("ignore 必须携带 reason");
            }
        }
    }

    private void savePreviewLog(RecoveryDeadLetterRequest request, RecoveryPreviewResult result) {
        RecoveryActionLogEntity existing = findActionLog(request.getActionRequestId());
        if (existing != null) {
            ensureSameAction(existing, request);
            return;
        }
        RecoveryActionLogEntity log = new RecoveryActionLogEntity();
        log.setActionRequestId(request.getActionRequestId());
        log.setActionType(request.getActionType());
        log.setTargetType(TARGET_DEAD_LETTER);
        log.setTargetKey(String.valueOf(request.getDeadLetterId()));
        log.setStatus(ACTION_PREVIEWED);
        log.setOperator(limit(request.getOperator(), 64));
        log.setReason(limit(request.getReason(), 512));
        log.setPreviewResult(toJson(result));
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        try {
            actionLogMapper.insert(log);
        } catch (DuplicateKeyException ignored) {
            ensureSameAction(findActionLog(request.getActionRequestId()), request);
        }
    }

    private RecoveryActionLogEntity prepareExecuteLog(
            RecoveryDeadLetterRequest request,
            RecoveryPreviewResult preview
    ) {
        RecoveryActionLogEntity existing = findActionLog(request.getActionRequestId());
        LocalDateTime now = LocalDateTime.now(clock);
        String executionOwner = executionOwner(request);
        LocalDateTime leaseUntil = now.plusSeconds(actionLeaseSeconds);
        if (existing != null) {
            ensureSameAction(existing, request);
            if (Objects.equals(existing.getStatus(), ACTION_SUBMITTED)) {
                return existing;
            }
            if (Objects.equals(existing.getStatus(), ACTION_EXECUTING)) {
                if (existing.getExecutionLeaseUntil() != null
                        && now.isBefore(existing.getExecutionLeaseUntil())) {
                    throw new BizException("ACTION_EXECUTING_LEASE_ACTIVE");
                }
                int rows = actionLogMapper.update(
                        null,
                        Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                                .eq(RecoveryActionLogEntity::getId, existing.getId())
                                .eq(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                                .and(lease -> lease
                                        .isNull(RecoveryActionLogEntity::getExecutionLeaseUntil)
                                        .or()
                                        .le(RecoveryActionLogEntity::getExecutionLeaseUntil, now))
                                .set(RecoveryActionLogEntity::getExecutionOwner, executionOwner)
                                .set(RecoveryActionLogEntity::getExecutionLeaseUntil, leaseUntil)
                                .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                                .set(RecoveryActionLogEntity::getReconcileCount,
                                        safeReconcileCount(existing) + 1)
                                .set(RecoveryActionLogEntity::getLastError, null)
                                .set(RecoveryActionLogEntity::getUpdatedAt, now)
                );
                if (rows != 1) {
                    throw new BizException("ACTION_EXECUTING_LEASE_CHANGED");
                }
                existing.setExecutionOwner(executionOwner);
                existing.setExecutionLeaseUntil(leaseUntil);
                existing.setLastHeartbeatAt(now);
                existing.setReconcileCount(safeReconcileCount(existing) + 1);
                return existing;
            }
            if (Objects.equals(existing.getStatus(), ACTION_FAILED)
                    || Objects.equals(existing.getStatus(), ACTION_MANUAL_REVIEW)) {
                throw new BizException("actionRequestId 已有确定失败或人工复核结果，必须重新预演");
            }
            int rows = actionLogMapper.update(
                    null,
                    Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                            .eq(RecoveryActionLogEntity::getId, existing.getId())
                            .eq(RecoveryActionLogEntity::getStatus, ACTION_PREVIEWED)
                            .set(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                            .set(RecoveryActionLogEntity::getOperator, limit(request.getOperator(), 64))
                             .set(RecoveryActionLogEntity::getReason, limit(request.getReason(), 512))
                             .set(RecoveryActionLogEntity::getPreviewResult, toJson(preview))
                             .set(RecoveryActionLogEntity::getExecutionOwner, executionOwner)
                             .set(RecoveryActionLogEntity::getExecutionLeaseUntil, leaseUntil)
                             .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                             .set(RecoveryActionLogEntity::getUpdatedAt, now)
            );
            if (rows != 1) {
                throw new BizException("恢复动作状态已变化，请重新查询");
            }
            existing.setStatus(ACTION_EXECUTING);
            existing.setExecutionOwner(executionOwner);
            existing.setExecutionLeaseUntil(leaseUntil);
            existing.setLastHeartbeatAt(now);
            return existing;
        }

        RecoveryActionLogEntity log = new RecoveryActionLogEntity();
        log.setActionRequestId(request.getActionRequestId());
        log.setActionType(request.getActionType());
        log.setTargetType(TARGET_DEAD_LETTER);
        log.setTargetKey(String.valueOf(request.getDeadLetterId()));
        log.setStatus(ACTION_EXECUTING);
        log.setOperator(limit(request.getOperator(), 64));
        log.setReason(limit(request.getReason(), 512));
        log.setPreviewResult(toJson(preview));
        log.setExecutionOwner(executionOwner);
        log.setExecutionLeaseUntil(leaseUntil);
        log.setLastHeartbeatAt(now);
        log.setReconcileCount(0);
        log.setCreatedAt(now);
        log.setUpdatedAt(now);
        actionLogMapper.insert(log);
        return log;
    }

    private RecoveryExecuteResult buildExecutingResult(RecoveryDeadLetterRequest request) {
        RecoveryExecuteResult result = new RecoveryExecuteResult();
        result.setActionRequestId(request.getActionRequestId());
        result.setActionType(request.getActionType());
        result.setTargetType(TARGET_DEAD_LETTER);
        result.setTargetKey(String.valueOf(request.getDeadLetterId()));
        result.setStatus("EXECUTING");
        return result;
    }

    private RecoveryExecuteResult idempotentSubmitted(RecoveryActionLogEntity log) {
        RecoveryExecuteResult result = new RecoveryExecuteResult();
        result.setActionRequestId(log.getActionRequestId());
        result.setActionType(log.getActionType());
        result.setTargetType(log.getTargetType());
        result.setTargetKey(log.getTargetKey());
        result.setStatus("IDEMPOTENT_SUBMITTED");
        result.setMessage("actionRequestId already submitted");
        result.setExecutedAt(log.getUpdatedAt());
        return result;
    }

    private void markExecuteSubmitted(Long id,
                                      String executionOwner,
                                      RecoveryExecuteResult result) {
        LocalDateTime now = LocalDateTime.now(clock);
        int rows = actionLogMapper.update(
                null,
                Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                        .eq(RecoveryActionLogEntity::getId, id)
                        .eq(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                        .eq(RecoveryActionLogEntity::getExecutionOwner, executionOwner)
                        .set(RecoveryActionLogEntity::getStatus, ACTION_SUBMITTED)
                        .set(RecoveryActionLogEntity::getExecuteResult, toJson(result))
                        .set(RecoveryActionLogEntity::getLastError, null)
                        .set(RecoveryActionLogEntity::getExecutionLeaseUntil, null)
                        .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                        .set(RecoveryActionLogEntity::getUpdatedAt, now)
        );
        if (rows != 1) {
            throw new BizException("ACTION_EXECUTION_LEASE_LOST");
        }
    }

    private void markExecuteFailed(Long id,
                                   String executionOwner,
                                   RecoveryExecuteResult result,
                                   String error) {
        LocalDateTime now = LocalDateTime.now(clock);
        actionLogMapper.update(
                null,
                Wrappers.<RecoveryActionLogEntity>lambdaUpdate()
                        .eq(RecoveryActionLogEntity::getId, id)
                        .eq(RecoveryActionLogEntity::getStatus, ACTION_EXECUTING)
                        .eq(RecoveryActionLogEntity::getExecutionOwner, executionOwner)
                        .set(RecoveryActionLogEntity::getStatus, ACTION_FAILED)
                        .set(RecoveryActionLogEntity::getExecuteResult, toJson(result))
                        .set(RecoveryActionLogEntity::getLastError, limit(error, 1024))
                        .set(RecoveryActionLogEntity::getExecutionLeaseUntil, null)
                        .set(RecoveryActionLogEntity::getLastHeartbeatAt, now)
                        .set(RecoveryActionLogEntity::getUpdatedAt, now)
        );
    }

    private String executionOwner(RecoveryDeadLetterRequest request) {
        if (StringUtils.hasText(request.getExecutionOwner())) {
            return limit(request.getExecutionOwner().trim(), 128);
        }
        return limit("operator:" + request.getOperator(), 128);
    }

    private int safeReconcileCount(RecoveryActionLogEntity action) {
        return action.getReconcileCount() == null ? 0 : action.getReconcileCount();
    }

    private RecoveryActionLogEntity findActionLog(String actionRequestId) {
        return actionLogMapper.selectOne(
                Wrappers.<RecoveryActionLogEntity>lambdaQuery()
                        .eq(RecoveryActionLogEntity::getActionRequestId, actionRequestId)
                        .last("limit 1")
        );
    }

    private void ensureSameAction(RecoveryActionLogEntity existing, RecoveryDeadLetterRequest request) {
        if (existing == null
                || !Objects.equals(existing.getActionType(), request.getActionType())
                || !Objects.equals(existing.getTargetType(), TARGET_DEAD_LETTER)
                || !Objects.equals(existing.getTargetKey(), String.valueOf(request.getDeadLetterId()))) {
            throw new BizException("actionRequestId 已用于其他恢复动作");
        }
    }

    private void fillOrder(ReservationRecoveryCheckResult result, String requestId) {
        try {
            ApiResponse<OrderQueryDto> response = orderClient.queryByRequestId(requestId);
            if (response == null) {
                result.setOrderQueryError("order-service response is null");
                return;
            }
            if (!Objects.equals(response.getCode(), 200)) {
                result.setOrderQueryError(response.getMessage());
                return;
            }
            result.setOrder(response.getData());
        } catch (RuntimeException exception) {
            result.setOrderQueryError(limit(exception.getMessage(), 512));
        }
    }

    private void fillInventoryInvariant(ReservationRecoveryCheckResult result) {
        StockItemEntity stock = result.getStockItem();
        if (stock == null) {
            result.setInventoryInvariantOk(false);
            result.getWarnings().add("stock item not found");
            return;
        }
        int diff = stock.getTotalStock()
                - stock.getAvailableStock()
                - stock.getLockedStock()
                - stock.getSoldStock();
        result.setInventoryDiff(diff);
        result.setInventoryInvariantOk(diff == 0
                && stock.getAvailableStock() >= 0
                && stock.getLockedStock() >= 0
                && stock.getSoldStock() >= 0);
    }

    private void fillWarnings(ReservationRecoveryCheckResult result) {
        if (!Boolean.TRUE.equals(result.getInventoryInvariantOk())) {
            result.getWarnings().add("stock invariant broken");
        }
        if (result.getReservationRequest() != null && result.getOrder() != null) {
            Integer requestOrderStatus = result.getReservationRequest().getOrderStatus();
            Integer orderStatus = result.getOrder().getStatus();
            if (requestOrderStatus != null
                    && orderStatus != null
                    && !Objects.equals(requestOrderStatus, orderStatus)) {
                result.getWarnings().add("reservation request orderStatus does not match order-service status");
            }
        }
        if (result.getDeductRecord() != null && result.getStockItem() != null) {
            Integer deductStatus = result.getDeductRecord().getStatus();
            Integer quantity = result.getDeductRecord().getQuantity();
            if (Objects.equals(deductStatus, 20)
                    && result.getStockItem().getLockedStock() < quantity) {
                result.getWarnings().add("deduct record is ORDER_CREATED but locked stock is insufficient");
            }
            if (Objects.equals(deductStatus, 60)
                    && result.getStockItem().getSoldStock() < quantity) {
                result.getWarnings().add("deduct record is SOLD but sold stock is insufficient");
            }
        }
        if (result.getDeductRecord() == null) {
            result.getWarnings().add("stock deduct record not found");
        }
        if (StringUtils.hasText(result.getOrderQueryError())) {
            result.getWarnings().add("order-service query failed: " + result.getOrderQueryError());
        }
        if (result.getOrder() != null
                && Boolean.FALSE.equals(result.getOrder().getExists())
                && result.getDeductRecord() != null) {
            result.getWarnings().add("deduct record exists but order-service reports order not found");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("serialize recovery result failed", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
