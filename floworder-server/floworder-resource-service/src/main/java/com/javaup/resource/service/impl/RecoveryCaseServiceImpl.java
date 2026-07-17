package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderQueryDto;
import com.javaup.enums.OrderStatusEnum;
import com.javaup.exception.BizException;
import com.javaup.resource.dto.RecoveryCaseDiagnosis;
import com.javaup.resource.dto.RecoveryCaseResult;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.RecoveryActionLogEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.enums.StockDeductStatusEnum;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.RecoveryActionLogMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.service.RecoveryCaseService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.javaup.constant.OrderMqConstant.ORDER_CANCELLED;
import static com.javaup.constant.OrderMqConstant.ORDER_TIMEOUT;

@Service
public class RecoveryCaseServiceImpl implements RecoveryCaseService {

    private static final int DEAD_PENDING = 0;
    private static final int DEAD_REPLAYING = 10;
    private static final int ACTION_EXECUTING = 10;
    private static final int ACTION_SUBMITTED = 20;
    private static final String TARGET_DEAD_LETTER = "DEAD_LETTER";
    private static final Set<Integer> TERMINAL_RECOVERABLE_ORDER_STATUSES = Set.of(
            OrderStatusEnum.CANCELLED.getCode(),
            OrderStatusEnum.TIMEOUT.getCode()
    );
    private static final Set<Integer> RECOVERABLE_DEDUCT_STATUSES = Set.of(
            StockDeductStatusEnum.PRE_DEDUCTED.getCode(),
            StockDeductStatusEnum.ORDER_CREATED.getCode()
    );
    private static final Set<String> SUPPORTED_EVENTS = Set.of(ORDER_CANCELLED, ORDER_TIMEOUT);

    private final ReservationRequestMapper requestMapper;
    private final StockDeductRecordMapper deductRecordMapper;
    private final StockItemMapper stockItemMapper;
    private final MqDeadLetterMapper deadLetterMapper;
    private final RecoveryActionLogMapper actionLogMapper;
    private final OrderClient orderClient;

    public RecoveryCaseServiceImpl(ReservationRequestMapper requestMapper,
                                   StockDeductRecordMapper deductRecordMapper,
                                   StockItemMapper stockItemMapper,
                                   MqDeadLetterMapper deadLetterMapper,
                                   RecoveryActionLogMapper actionLogMapper,
                                   OrderClient orderClient) {
        this.requestMapper = requestMapper;
        this.deductRecordMapper = deductRecordMapper;
        this.stockItemMapper = stockItemMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.actionLogMapper = actionLogMapper;
        this.orderClient = orderClient;
    }

    @Override
    public RecoveryCaseResult inspect(String identifierType, String identifierValue) {
        String normalizedType = normalizeIdentifierType(identifierType);
        String normalizedValue = requireIdentifierValue(identifierValue);
        LocatedCase located = locate(normalizedType, normalizedValue);

        ReservationRequestEntity reservation = located.reservation();
        StockDeductRecordEntity deduct = located.deduct();
        String requestId = canonicalRequestId(reservation, deduct, normalizedType, normalizedValue);
        if (reservation == null && StringUtils.hasText(requestId)) {
            reservation = findReservationByRequestId(requestId);
        }
        if (deduct == null && StringUtils.hasText(requestId)) {
            deduct = findDeductByRequestId(requestId);
        }

        StockItemEntity stock = findStock(reservation, deduct);
        List<MqDeadLetterEntity> deadLetters = findRelatedDeadLetters(deduct, located.seedDeadLetter());
        List<RecoveryActionLogEntity> actions = findRelatedActions(deadLetters);
        RecoveryCaseResult.OrderFact order = queryOrder(requestId);

        RecoveryCaseResult result = new RecoveryCaseResult();
        result.setIdentifierType(normalizedType);
        result.setIdentifierValue(normalizedValue);
        result.setCanonicalRequestId(requestId);
        result.setCaseKey(caseKey(requestId, normalizedType, normalizedValue));
        result.setGeneratedAt(LocalDateTime.now());
        result.setReservation(toReservationFact(reservation));
        result.setOrder(order);
        result.setDeduct(toDeductFact(deduct));
        result.setInventory(toInventoryFact(stock));
        result.setDeadLetters(deadLetters.stream().map(this::toDeadLetterFact).toList());
        result.setRecoveryActions(actions.stream().map(this::toActionFact).toList());
        result.setFound(caseFound(reservation, deduct, located.seedDeadLetter(), order));
        result.setFactsComplete(factsComplete(reservation, deduct, stock, order));

        collectEvidence(result, reservation, deduct, stock, deadLetters, actions, order);
        collectHardRisks(result, reservation, deduct, stock, deadLetters, actions, order);
        RecoveryCaseDiagnosis diagnosis = diagnose(result, reservation, deduct, deadLetters, actions, order);
        result.setDiagnosisCode(diagnosis.name());
        result.setRecoveryEligible(diagnosis == RecoveryCaseDiagnosis.REPLAY_CANDIDATE);
        result.setCandidates(buildCandidates(deadLetters, diagnosis));
        return result;
    }

    private LocatedCase locate(String identifierType, String identifierValue) {
        return switch (identifierType) {
            case "REQUEST_ID" -> new LocatedCase(
                    findReservationByRequestId(identifierValue),
                    findDeductByRequestId(identifierValue),
                    null
            );
            case "ORDER_NO" -> {
                ReservationRequestEntity reservation = requestMapper.selectOne(
                        Wrappers.<ReservationRequestEntity>lambdaQuery()
                                .eq(ReservationRequestEntity::getOrderNo, identifierValue)
                                .last("limit 1")
                );
                StockDeductRecordEntity deduct = deductRecordMapper.selectOne(
                        Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                .eq(StockDeductRecordEntity::getOrderNo, identifierValue)
                                .last("limit 1")
                );
                yield new LocatedCase(reservation, deduct, null);
            }
            case "DEDUCT_NO" -> {
                StockDeductRecordEntity deduct = deductRecordMapper.selectOne(
                        Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                .eq(StockDeductRecordEntity::getDeductNo, identifierValue)
                                .last("limit 1")
                );
                yield new LocatedCase(null, deduct, null);
            }
            case "DEAD_LETTER_ID" -> {
                Long deadLetterId;
                try {
                    deadLetterId = Long.valueOf(identifierValue);
                } catch (NumberFormatException exception) {
                    throw new BizException("deadLetterId必须是数字");
                }
                MqDeadLetterEntity deadLetter = deadLetterMapper.selectById(deadLetterId);
                StockDeductRecordEntity deduct = deadLetter == null || !StringUtils.hasText(deadLetter.getBizKey())
                        ? null
                        : deductRecordMapper.selectOne(
                                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                        .eq(StockDeductRecordEntity::getDeductNo, deadLetter.getBizKey())
                                        .last("limit 1")
                        );
                yield new LocatedCase(null, deduct, deadLetter);
            }
            default -> throw new BizException("不支持的identifierType：" + identifierType);
        };
    }

    private ReservationRequestEntity findReservationByRequestId(String requestId) {
        return requestMapper.selectOne(
                Wrappers.<ReservationRequestEntity>lambdaQuery()
                        .eq(ReservationRequestEntity::getRequestId, requestId)
                        .last("limit 1")
        );
    }

    private StockDeductRecordEntity findDeductByRequestId(String requestId) {
        return deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getRequestId, requestId)
                        .last("limit 1")
        );
    }

    private StockItemEntity findStock(ReservationRequestEntity reservation, StockDeductRecordEntity deduct) {
        Long stockItemId = deduct != null ? deduct.getStockItemId()
                : reservation == null ? null : reservation.getStockItemId();
        return stockItemId == null ? null : stockItemMapper.selectById(stockItemId);
    }

    private List<MqDeadLetterEntity> findRelatedDeadLetters(StockDeductRecordEntity deduct,
                                                             MqDeadLetterEntity seedDeadLetter) {
        List<MqDeadLetterEntity> records = new ArrayList<>();
        if (deduct != null && StringUtils.hasText(deduct.getDeductNo())) {
            List<MqDeadLetterEntity> selected = deadLetterMapper.selectList(
                    Wrappers.<MqDeadLetterEntity>lambdaQuery()
                            .eq(MqDeadLetterEntity::getBizKey, deduct.getDeductNo())
                            .orderByDesc(MqDeadLetterEntity::getCreatedAt)
                            .last("limit 50")
            );
            if (selected != null) {
                records.addAll(selected);
            }
        }
        if (seedDeadLetter != null
                && records.stream().noneMatch(item -> Objects.equals(item.getId(), seedDeadLetter.getId()))) {
            records.add(seedDeadLetter);
        }
        return List.copyOf(records);
    }

    private List<RecoveryActionLogEntity> findRelatedActions(List<MqDeadLetterEntity> deadLetters) {
        List<String> targetKeys = deadLetters.stream()
                .map(MqDeadLetterEntity::getId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        if (targetKeys.isEmpty()) {
            return List.of();
        }
        List<RecoveryActionLogEntity> actions = actionLogMapper.selectList(
                Wrappers.<RecoveryActionLogEntity>lambdaQuery()
                        .eq(RecoveryActionLogEntity::getTargetType, TARGET_DEAD_LETTER)
                        .in(RecoveryActionLogEntity::getTargetKey, targetKeys)
                        .orderByDesc(RecoveryActionLogEntity::getUpdatedAt)
                        .last("limit 50")
        );
        return actions == null ? List.of() : List.copyOf(actions);
    }

    private RecoveryCaseResult.OrderFact queryOrder(String requestId) {
        RecoveryCaseResult.OrderFact fact = new RecoveryCaseResult.OrderFact();
        if (!StringUtils.hasText(requestId)) {
            fact.setDependencyAvailable(true);
            fact.setExists(false);
            return fact;
        }
        try {
            ApiResponse<OrderQueryDto> response = orderClient.queryByRequestId(requestId);
            if (response == null || !Objects.equals(response.getCode(), 200)) {
                fact.setDependencyAvailable(false);
                fact.setExists(false);
                fact.setQueryError(limit(response == null ? "order-service response is null" : response.getMessage()));
                return fact;
            }
            OrderQueryDto order = response.getData();
            fact.setDependencyAvailable(true);
            fact.setExists(order != null && Boolean.TRUE.equals(order.getExists()));
            if (order != null) {
                fact.setOrderNo(order.getOrderNo());
                fact.setStatus(order.getStatus());
                fact.setStatusName(orderStatusName(order.getStatus()));
            }
            return fact;
        } catch (RuntimeException exception) {
            fact.setDependencyAvailable(false);
            fact.setExists(false);
            fact.setQueryError(limit(exception.getMessage()));
            return fact;
        }
    }

    private RecoveryCaseDiagnosis diagnose(RecoveryCaseResult result,
                                           ReservationRequestEntity reservation,
                                           StockDeductRecordEntity deduct,
                                           List<MqDeadLetterEntity> deadLetters,
                                           List<RecoveryActionLogEntity> actions,
                                           RecoveryCaseResult.OrderFact order) {
        if (!Boolean.TRUE.equals(result.getFound())) {
            return RecoveryCaseDiagnosis.NO_RECOVERY_EVIDENCE;
        }
        if (hasFactConflict(reservation, deduct, result.getInventory(), deadLetters, order)) {
            return RecoveryCaseDiagnosis.FACT_CONFLICT;
        }
        if (!Boolean.TRUE.equals(order.getDependencyAvailable())) {
            return RecoveryCaseDiagnosis.DEPENDENCY_UNAVAILABLE;
        }
        if (isConverged(deduct, result.getInventory(), order)) {
            return RecoveryCaseDiagnosis.ALREADY_CONVERGED;
        }
        if (hasActionInProgress(deadLetters, actions)) {
            return RecoveryCaseDiagnosis.ACTION_IN_PROGRESS;
        }
        if (hasUnsupportedUnresolvedEvent(deadLetters)) {
            return RecoveryCaseDiagnosis.UNSUPPORTED_EVENT;
        }
        if (isReplayCandidate(deduct, deadLetters, order)) {
            return RecoveryCaseDiagnosis.REPLAY_CANDIDATE;
        }
        return RecoveryCaseDiagnosis.NO_RECOVERY_EVIDENCE;
    }

    private boolean hasFactConflict(ReservationRequestEntity reservation,
                                    StockDeductRecordEntity deduct,
                                    RecoveryCaseResult.InventoryFact inventory,
                                    List<MqDeadLetterEntity> deadLetters,
                                    RecoveryCaseResult.OrderFact order) {
        if (Boolean.TRUE.equals(inventory.getExists()) && !Boolean.TRUE.equals(inventory.getInvariantOk())) {
            return true;
        }
        if (reservation != null
                && Boolean.TRUE.equals(order.getDependencyAvailable())
                && Boolean.TRUE.equals(order.getExists())
                && reservation.getOrderStatus() != null
                && order.getStatus() != null
                && !Objects.equals(reservation.getOrderStatus(), order.getStatus())
                && !isOrderStatusGapExplainedByDeadLetter(reservation, deadLetters, order)) {
            return true;
        }
        if (deduct != null
                && Boolean.TRUE.equals(order.getDependencyAvailable())
                && Boolean.FALSE.equals(order.getExists())) {
            return true;
        }
        if (deduct == null || order.getStatus() == null) {
            return false;
        }
        return TERMINAL_RECOVERABLE_ORDER_STATUSES.contains(order.getStatus())
                && Objects.equals(deduct.getStatus(), StockDeductStatusEnum.SOLD.getCode());
    }

    private boolean isOrderStatusGapExplainedByDeadLetter(ReservationRequestEntity reservation,
                                                          List<MqDeadLetterEntity> deadLetters,
                                                          RecoveryCaseResult.OrderFact order) {
        if (reservation == null || order.getStatus() == null
                || Objects.equals(reservation.getOrderStatus(), order.getStatus())) {
            return false;
        }
        String expectedEvent;
        if (Objects.equals(order.getStatus(), OrderStatusEnum.TIMEOUT.getCode())) {
            expectedEvent = ORDER_TIMEOUT;
        } else if (Objects.equals(order.getStatus(), OrderStatusEnum.CANCELLED.getCode())) {
            expectedEvent = ORDER_CANCELLED;
        } else {
            return false;
        }
        return deadLetters.stream().anyMatch(item ->
                isUnresolved(item) && expectedEvent.equals(item.getMessageType()));
    }

    private boolean isConverged(StockDeductRecordEntity deduct,
                                RecoveryCaseResult.InventoryFact inventory,
                                RecoveryCaseResult.OrderFact order) {
        return deduct != null
                && TERMINAL_RECOVERABLE_ORDER_STATUSES.contains(order.getStatus())
                && Objects.equals(deduct.getStatus(), StockDeductStatusEnum.RELEASED.getCode())
                && Boolean.TRUE.equals(inventory.getInvariantOk());
    }

    private boolean hasActionInProgress(List<MqDeadLetterEntity> deadLetters,
                                        List<RecoveryActionLogEntity> actions) {
        boolean replaying = deadLetters.stream()
                .anyMatch(item -> Objects.equals(item.getStatus(), DEAD_REPLAYING));
        boolean actionRunning = actions.stream().anyMatch(item ->
                Objects.equals(item.getStatus(), ACTION_EXECUTING)
                        || Objects.equals(item.getStatus(), ACTION_SUBMITTED));
        return replaying || actionRunning;
    }

    private boolean hasUnsupportedUnresolvedEvent(List<MqDeadLetterEntity> deadLetters) {
        return deadLetters.stream().anyMatch(item -> isUnresolved(item)
                && !SUPPORTED_EVENTS.contains(item.getMessageType()));
    }

    private boolean isReplayCandidate(StockDeductRecordEntity deduct,
                                      List<MqDeadLetterEntity> deadLetters,
                                      RecoveryCaseResult.OrderFact order) {
        return deduct != null
                && Boolean.TRUE.equals(order.getExists())
                && TERMINAL_RECOVERABLE_ORDER_STATUSES.contains(order.getStatus())
                && RECOVERABLE_DEDUCT_STATUSES.contains(deduct.getStatus())
                && deadLetters.stream().anyMatch(item ->
                        Objects.equals(item.getStatus(), DEAD_PENDING)
                                && SUPPORTED_EVENTS.contains(item.getMessageType()));
    }

    private List<RecoveryCaseResult.RecoveryCandidate> buildCandidates(
            List<MqDeadLetterEntity> deadLetters,
            RecoveryCaseDiagnosis diagnosis) {
        return deadLetters.stream()
                .filter(item -> Objects.equals(item.getStatus(), DEAD_PENDING))
                .filter(item -> SUPPORTED_EVENTS.contains(item.getMessageType()))
                .map(item -> {
                    RecoveryCaseResult.RecoveryCandidate candidate = new RecoveryCaseResult.RecoveryCandidate();
                    candidate.setCandidateId("replay-dead-letter-" + item.getId());
                    candidate.setActionType("REPLAY");
                    candidate.setTargetType(TARGET_DEAD_LETTER);
                    candidate.setTargetKey(String.valueOf(item.getId()));
                    candidate.setEligible(diagnosis == RecoveryCaseDiagnosis.REPLAY_CANDIDATE);
                    candidate.setDecisionOwner("FLOWORDER");
                    candidate.setBlockedBy(diagnosis == RecoveryCaseDiagnosis.REPLAY_CANDIDATE
                            ? ""
                            : diagnosis.name());
                    return candidate;
                })
                .toList();
    }

    private void collectEvidence(RecoveryCaseResult result,
                                 ReservationRequestEntity reservation,
                                 StockDeductRecordEntity deduct,
                                 StockItemEntity stock,
                                 List<MqDeadLetterEntity> deadLetters,
                                 List<RecoveryActionLogEntity> actions,
                                 RecoveryCaseResult.OrderFact order) {
        if (reservation != null) {
            result.getEvidence().add("RESERVATION_FOUND");
        }
        if (deduct != null) {
            result.getEvidence().add("DEDUCT_FOUND");
        }
        if (stock != null) {
            result.getEvidence().add("STOCK_ITEM_FOUND");
        }
        if (Boolean.TRUE.equals(order.getDependencyAvailable())) {
            result.getEvidence().add(Boolean.TRUE.equals(order.getExists()) ? "ORDER_FOUND" : "ORDER_NOT_FOUND");
        }
        if (!deadLetters.isEmpty()) {
            result.getEvidence().add("RELATED_DEAD_LETTER_FOUND");
        }
        if (isOrderStatusGapExplainedByDeadLetter(reservation, deadLetters, order)) {
            result.getEvidence().add("ORDER_STATUS_GAP_EXPLAINED_BY_DEAD_LETTER");
        }
        if (!actions.isEmpty()) {
            result.getEvidence().add("RECOVERY_ACTION_HISTORY_FOUND");
        }
    }

    private void collectHardRisks(RecoveryCaseResult result,
                                  ReservationRequestEntity reservation,
                                  StockDeductRecordEntity deduct,
                                  StockItemEntity stock,
                                  List<MqDeadLetterEntity> deadLetters,
                                  List<RecoveryActionLogEntity> actions,
                                  RecoveryCaseResult.OrderFact order) {
        if (!Boolean.TRUE.equals(order.getDependencyAvailable())) {
            result.getHardRisks().add("ORDER_DEPENDENCY_UNAVAILABLE");
        }
        if (stock == null) {
            result.getHardRisks().add("STOCK_ITEM_MISSING");
        } else if (!Boolean.TRUE.equals(result.getInventory().getInvariantOk())) {
            result.getHardRisks().add("INVENTORY_INVARIANT_BROKEN");
        }
        if (reservation != null && Boolean.TRUE.equals(order.getExists())
                && reservation.getOrderStatus() != null && order.getStatus() != null
                && !Objects.equals(reservation.getOrderStatus(), order.getStatus())
                && !isOrderStatusGapExplainedByDeadLetter(reservation, deadLetters, order)) {
            result.getHardRisks().add("ORDER_STATUS_CONFLICT");
        }
        if (deduct != null && Boolean.TRUE.equals(order.getDependencyAvailable())
                && Boolean.FALSE.equals(order.getExists())) {
            result.getHardRisks().add("ORDER_NOT_FOUND_WITH_DEDUCT");
        }
        if (deduct != null && Objects.equals(deduct.getStatus(), StockDeductStatusEnum.MANUAL_REVIEW.getCode())) {
            result.getHardRisks().add("DEDUCT_MANUAL_REVIEW");
        }
        if (hasUnsupportedUnresolvedEvent(deadLetters)) {
            result.getHardRisks().add("UNSUPPORTED_DEAD_LETTER_EVENT");
        }
        if (hasActionInProgress(deadLetters, actions)) {
            result.getHardRisks().add("RECOVERY_ACTION_IN_PROGRESS");
        }
        if (deadLetters.isEmpty()) {
            result.getHardRisks().add("NO_RELATED_DEAD_LETTER");
        }
    }

    private RecoveryCaseResult.ReservationFact toReservationFact(ReservationRequestEntity source) {
        RecoveryCaseResult.ReservationFact fact = new RecoveryCaseResult.ReservationFact();
        fact.setExists(source != null);
        if (source == null) {
            return fact;
        }
        fact.setId(source.getId());
        fact.setRequestId(source.getRequestId());
        fact.setTraceId(source.getTraceId());
        fact.setStatus(source.getStatus());
        fact.setStatusName(reservationStatusName(source.getStatus()));
        fact.setOrderNo(source.getOrderNo());
        fact.setOrderStatus(source.getOrderStatus());
        fact.setOrderStatusName(orderStatusName(source.getOrderStatus()));
        fact.setLatestOrderEventType(source.getLatestOrderEventType());
        fact.setLatestOrderEventTime(source.getLatestOrderEventTime());
        fact.setOrderEventVersion(source.getOrderEventVersion());
        fact.setLastError(bounded(source.getLastError()));
        return fact;
    }

    private RecoveryCaseResult.DeductFact toDeductFact(StockDeductRecordEntity source) {
        RecoveryCaseResult.DeductFact fact = new RecoveryCaseResult.DeductFact();
        fact.setExists(source != null);
        if (source == null) {
            return fact;
        }
        fact.setId(source.getId());
        fact.setDeductNo(source.getDeductNo());
        fact.setOrderNo(source.getOrderNo());
        fact.setStockItemId(source.getStockItemId());
        fact.setQuantity(source.getQuantity());
        fact.setStatus(source.getStatus());
        fact.setStatusName(deductStatusName(source.getStatus()));
        fact.setReleaseReason(bounded(source.getReleaseReason()));
        fact.setLastError(bounded(source.getLastError()));
        fact.setUpdatedAt(source.getUpdatedAt());
        return fact;
    }

    private RecoveryCaseResult.InventoryFact toInventoryFact(StockItemEntity source) {
        RecoveryCaseResult.InventoryFact fact = new RecoveryCaseResult.InventoryFact();
        fact.setExists(source != null);
        if (source == null) {
            fact.setInvariantOk(false);
            return fact;
        }
        fact.setStockItemId(source.getId());
        fact.setTotalStock(source.getTotalStock());
        fact.setAvailableStock(source.getAvailableStock());
        fact.setLockedStock(source.getLockedStock());
        fact.setSoldStock(source.getSoldStock());
        int diff = safeInt(source.getTotalStock())
                - safeInt(source.getAvailableStock())
                - safeInt(source.getLockedStock())
                - safeInt(source.getSoldStock());
        fact.setInvariantDiff(diff);
        fact.setInvariantOk(diff == 0
                && nonNegative(source.getAvailableStock())
                && nonNegative(source.getLockedStock())
                && nonNegative(source.getSoldStock()));
        fact.setVersion(source.getVersion());
        fact.setUpdatedAt(source.getUpdatedAt());
        return fact;
    }

    private RecoveryCaseResult.DeadLetterFact toDeadLetterFact(MqDeadLetterEntity source) {
        RecoveryCaseResult.DeadLetterFact fact = new RecoveryCaseResult.DeadLetterFact();
        fact.setDeadLetterId(source.getId());
        fact.setMessageId(source.getMessageId());
        fact.setDeadQueue(source.getDeadQueue());
        fact.setProducerService(source.getProducerService());
        fact.setMessageType(source.getMessageType());
        fact.setBizKey(source.getBizKey());
        fact.setStatus(source.getStatus());
        fact.setStatusName(deadLetterStatusName(source.getStatus()));
        fact.setReplayCount(source.getReplayCount());
        fact.setDeathReason(bounded(source.getDeathReason()));
        fact.setLastError(bounded(source.getLastError()));
        fact.setReplayedAt(source.getReplayedAt());
        fact.setResolvedAt(source.getResolvedAt());
        fact.setUpdatedAt(source.getUpdatedAt());
        return fact;
    }

    private RecoveryCaseResult.RecoveryActionFact toActionFact(RecoveryActionLogEntity source) {
        RecoveryCaseResult.RecoveryActionFact fact = new RecoveryCaseResult.RecoveryActionFact();
        fact.setActionId(source.getId());
        fact.setActionRequestId(source.getActionRequestId());
        fact.setActionType(source.getActionType());
        fact.setTargetType(source.getTargetType());
        fact.setTargetKey(source.getTargetKey());
        fact.setStatus(source.getStatus());
        fact.setStatusName(actionStatusName(source.getStatus()));
        fact.setExecutionOwner(source.getExecutionOwner());
        fact.setExecutionLeaseUntil(source.getExecutionLeaseUntil());
        fact.setLastHeartbeatAt(source.getLastHeartbeatAt());
        fact.setReconcileCount(source.getReconcileCount());
        fact.setLastError(bounded(source.getLastError()));
        fact.setUpdatedAt(source.getUpdatedAt());
        return fact;
    }

    private String canonicalRequestId(ReservationRequestEntity reservation,
                                      StockDeductRecordEntity deduct,
                                      String identifierType,
                                      String identifierValue) {
        if (reservation != null && StringUtils.hasText(reservation.getRequestId())) {
            return reservation.getRequestId();
        }
        if (deduct != null && StringUtils.hasText(deduct.getRequestId())) {
            return deduct.getRequestId();
        }
        return "REQUEST_ID".equals(identifierType) ? identifierValue : null;
    }

    private boolean caseFound(ReservationRequestEntity reservation,
                              StockDeductRecordEntity deduct,
                              MqDeadLetterEntity seed,
                              RecoveryCaseResult.OrderFact order) {
        return reservation != null || deduct != null || seed != null || Boolean.TRUE.equals(order.getExists());
    }

    private boolean factsComplete(ReservationRequestEntity reservation,
                                  StockDeductRecordEntity deduct,
                                  StockItemEntity stock,
                                  RecoveryCaseResult.OrderFact order) {
        return reservation != null
                && deduct != null
                && stock != null
                && Boolean.TRUE.equals(order.getDependencyAvailable())
                && Boolean.TRUE.equals(order.getExists());
    }

    private boolean isUnresolved(MqDeadLetterEntity deadLetter) {
        return Objects.equals(deadLetter.getStatus(), DEAD_PENDING)
                || Objects.equals(deadLetter.getStatus(), DEAD_REPLAYING);
    }

    private String normalizeIdentifierType(String identifierType) {
        if (!StringUtils.hasText(identifierType)) {
            throw new BizException("identifierType不能为空");
        }
        return identifierType.trim().toUpperCase(Locale.ROOT);
    }

    private String requireIdentifierValue(String identifierValue) {
        if (!StringUtils.hasText(identifierValue)) {
            throw new BizException("identifierValue不能为空");
        }
        String normalized = identifierValue.trim();
        if (normalized.length() > 128) {
            throw new BizException("identifierValue长度不能超过128");
        }
        return normalized;
    }

    private String caseKey(String requestId, String identifierType, String identifierValue) {
        return StringUtils.hasText(requestId)
                ? "floworder:request:" + requestId
                : "floworder:unresolved:" + identifierType + ":" + identifierValue;
    }

    private String reservationStatusName(Integer status) {
        return Arrays.stream(ReservationRequestStatusEnum.values())
                .filter(item -> Objects.equals(item.getStatus(), status))
                .map(Enum::name)
                .findFirst()
                .orElse("UNKNOWN");
    }

    private String orderStatusName(Integer status) {
        return Arrays.stream(OrderStatusEnum.values())
                .filter(item -> Objects.equals(item.getCode(), status))
                .map(Enum::name)
                .findFirst()
                .orElse("UNKNOWN");
    }

    private String deductStatusName(Integer status) {
        return Arrays.stream(StockDeductStatusEnum.values())
                .filter(item -> Objects.equals(item.getCode(), status))
                .map(Enum::name)
                .findFirst()
                .orElse("UNKNOWN");
    }

    private String deadLetterStatusName(Integer status) {
        if (Objects.equals(status, 0)) {
            return "PENDING";
        }
        if (Objects.equals(status, 10)) {
            return "REPLAYING";
        }
        if (Objects.equals(status, 20)) {
            return "RESOLVED";
        }
        if (Objects.equals(status, 30)) {
            return "IGNORED";
        }
        return "UNKNOWN";
    }

    private String actionStatusName(Integer status) {
        if (Objects.equals(status, 0)) {
            return "PREVIEWED";
        }
        if (Objects.equals(status, 10)) {
            return "EXECUTING";
        }
        if (Objects.equals(status, 20)) {
            return "SUBMITTED";
        }
        if (Objects.equals(status, 30)) {
            return "FAILED";
        }
        if (Objects.equals(status, 40)) {
            return "MANUAL_REVIEW";
        }
        return "UNKNOWN";
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean nonNegative(Integer value) {
        return value != null && value >= 0;
    }

    private String limit(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown dependency error";
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private record LocatedCase(ReservationRequestEntity reservation,
                               StockDeductRecordEntity deduct,
                               MqDeadLetterEntity seedDeadLetter) {
    }
}
