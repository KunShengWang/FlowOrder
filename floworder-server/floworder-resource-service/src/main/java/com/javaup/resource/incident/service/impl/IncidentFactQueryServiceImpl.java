package com.javaup.resource.incident.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderFactBatchRequest;
import com.javaup.dto.OrderFactBatchResult;
import com.javaup.dto.OrderFactItemDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.enums.StockDeductStatusEnum;
import com.javaup.resource.incident.dto.IncidentDeadLetterFacts;
import com.javaup.resource.incident.dto.IncidentFactQueryRequest;
import com.javaup.resource.incident.dto.IncidentFactResponse;
import com.javaup.resource.incident.dto.IncidentInventoryFacts;
import com.javaup.resource.incident.dto.IncidentOrderFacts;
import com.javaup.resource.incident.service.IncidentFactQueryService;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IncidentFactQueryServiceImpl implements IncidentFactQueryService {

    private static final String SCHEMA_VERSION = "floworder-incident-facts-v1";
    private static final String SOURCE_SYSTEM = "floworder-resource-service";
    private static final int MAX_REQUEST_IDS = 100;
    private static final int DEFAULT_MAX_RECORDS = 100;
    private static final int MAX_RECORDS = 500;

    private final ReservationRequestMapper reservationRequestMapper;
    private final StockDeductRecordMapper stockDeductRecordMapper;
    private final StockItemMapper stockItemMapper;
    private final MqDeadLetterMapper mqDeadLetterMapper;
    private final OrderClient orderClient;

    @Override
    public IncidentFactResponse<IncidentOrderFacts> queryOrders(IncidentFactQueryRequest request) {
        QueryScope scope = normalize(request, false);
        Map<String, ReservationRequestEntity> reservations = reservationRequestMapper.selectList(
                        Wrappers.<ReservationRequestEntity>lambdaQuery()
                                .in(ReservationRequestEntity::getRequestId, scope.requestIds())
                                .orderByAsc(ReservationRequestEntity::getRequestId))
                .stream()
                .collect(Collectors.toMap(
                        ReservationRequestEntity::getRequestId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        OrderDependencyResult dependency = queryOrderService(scope.requestIds());
        Map<String, OrderFactItemDto> orders = dependency.items().stream()
                .collect(Collectors.toMap(
                        OrderFactItemDto::getRequestId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<IncidentOrderFacts.OrderFact> items = new ArrayList<>();
        TreeSet<String> existingRequestIds = new TreeSet<>();
        TreeSet<String> terminalRequestIds = new TreeSet<>();
        TreeSet<String> missingRequestIds = new TreeSet<>();
        for (String requestId : scope.requestIds()) {
            ReservationRequestEntity reservation = reservations.get(requestId);
            OrderFactItemDto order = orders.get(requestId);
            IncidentOrderFacts.OrderFact item = toOrderFact(
                    requestId,
                    reservation,
                    order,
                    dependency.available());
            items.add(item);
            if (Boolean.TRUE.equals(item.getOrderExists())) {
                existingRequestIds.add(requestId);
                if (isTerminalOrderStatus(item.getOrderStatus())) {
                    terminalRequestIds.add(requestId);
                }
            } else if (dependency.available()) {
                missingRequestIds.add(requestId);
            }
        }

        IncidentOrderFacts facts = new IncidentOrderFacts();
        facts.setRecordCount(existingRequestIds.size());
        facts.setDistinctRequestIdCount(existingRequestIds.size());
        facts.setTerminalDistinctRequestIdCount(terminalRequestIds.size());
        facts.setRequestIds(List.copyOf(existingRequestIds));
        facts.setTerminalRequestIds(List.copyOf(terminalRequestIds));
        facts.setItems(List.copyOf(items));
        return response(scope, "order-facts", false, List.copyOf(missingRequestIds), facts);
    }

    @Override
    public IncidentFactResponse<IncidentInventoryFacts> queryInventory(IncidentFactQueryRequest request) {
        QueryScope scope = normalize(request, false);
        List<StockDeductRecordEntity> deducts = stockDeductRecordMapper.selectList(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .in(StockDeductRecordEntity::getRequestId, scope.requestIds())
                        .orderByAsc(StockDeductRecordEntity::getRequestId)
                        .orderByAsc(StockDeductRecordEntity::getDeductNo));

        TreeSet<Long> stockItemIds = deducts.stream()
                .map(StockDeductRecordEntity::getStockItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Long, StockItemEntity> stockItems = stockItemIds.isEmpty()
                ? Map.of()
                : stockItemMapper.selectList(Wrappers.<StockItemEntity>lambdaQuery()
                                .in(StockItemEntity::getId, stockItemIds)
                                .orderByAsc(StockItemEntity::getId))
                        .stream()
                        .collect(Collectors.toMap(StockItemEntity::getId, Function.identity()));

        List<IncidentInventoryFacts.InventoryFact> items = new ArrayList<>();
        TreeSet<String> requestIds = new TreeSet<>();
        TreeSet<String> unreleasedRequestIds = new TreeSet<>();
        TreeSet<Long> invariantViolations = new TreeSet<>();
        for (StockDeductRecordEntity deduct : deducts) {
            requestIds.add(deduct.getRequestId());
            if (isUnreleased(deduct.getStatus())) {
                unreleasedRequestIds.add(deduct.getRequestId());
            }
            IncidentInventoryFacts.InventoryFact item = toInventoryFact(
                    deduct,
                    stockItems.get(deduct.getStockItemId()));
            items.add(item);
            if (Boolean.FALSE.equals(item.getInventoryInvariantOk()) && item.getStockItemId() != null) {
                invariantViolations.add(item.getStockItemId());
            }
        }

        TreeSet<String> missingRequestIds = new TreeSet<>(scope.requestIds());
        missingRequestIds.removeAll(requestIds);

        IncidentInventoryFacts facts = new IncidentInventoryFacts();
        facts.setRecordCount(deducts.size());
        facts.setDistinctRequestIdCount(requestIds.size());
        facts.setUnreleasedDistinctRequestIdCount(unreleasedRequestIds.size());
        facts.setRequestIds(List.copyOf(requestIds));
        facts.setUnreleasedRequestIds(List.copyOf(unreleasedRequestIds));
        facts.setInvariantViolationStockItemIds(List.copyOf(invariantViolations));
        facts.setItems(List.copyOf(items));
        return response(scope, "inventory-facts", false, List.copyOf(missingRequestIds), facts);
    }

    @Override
    public IncidentFactResponse<IncidentDeadLetterFacts> queryDeadLetters(IncidentFactQueryRequest request) {
        QueryScope scope = normalize(request, true);
        List<StockDeductRecordEntity> deducts = stockDeductRecordMapper.selectList(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .in(StockDeductRecordEntity::getRequestId, scope.requestIds())
                        .orderByAsc(StockDeductRecordEntity::getRequestId));
        Map<String, String> requestIdByBizKey = deducts.stream()
                .filter(deduct -> StringUtils.hasText(deduct.getDeductNo()))
                .collect(Collectors.toMap(
                        StockDeductRecordEntity::getDeductNo,
                        StockDeductRecordEntity::getRequestId,
                        (left, right) -> left,
                        TreeMap::new));

        if (requestIdByBizKey.isEmpty()) {
            return emptyDeadLetterResponse(scope);
        }

        var query = Wrappers.<MqDeadLetterEntity>lambdaQuery()
                .in(MqDeadLetterEntity::getBizKey, requestIdByBizKey.keySet())
                .in(MqDeadLetterEntity::getDeadQueue, scope.queueNames());
        long totalMatching = mqDeadLetterMapper.selectCount(query);
        List<MqDeadLetterEntity> deadLetters = mqDeadLetterMapper.selectList(
                Wrappers.<MqDeadLetterEntity>lambdaQuery()
                        .in(MqDeadLetterEntity::getBizKey, requestIdByBizKey.keySet())
                        .in(MqDeadLetterEntity::getDeadQueue, scope.queueNames())
                        .orderByAsc(MqDeadLetterEntity::getId)
                        .last("LIMIT " + scope.maxRecords()));

        TreeSet<String> bizKeys = new TreeSet<>();
        TreeSet<String> requestIds = new TreeSet<>();
        TreeSet<Long> deadLetterIds = new TreeSet<>();
        Map<String, List<Long>> idsByBizKey = new TreeMap<>();
        int unmappedRecordCount = 0;
        List<IncidentDeadLetterFacts.DeadLetterFact> items = new ArrayList<>();
        for (MqDeadLetterEntity deadLetter : deadLetters) {
            String bizKey = normalizeNullable(deadLetter.getBizKey());
            String requestId = bizKey == null ? null : requestIdByBizKey.get(bizKey);
            if (bizKey == null || requestId == null) {
                unmappedRecordCount++;
            } else {
                bizKeys.add(bizKey);
                requestIds.add(requestId);
                idsByBizKey.computeIfAbsent(bizKey, ignored -> new ArrayList<>()).add(deadLetter.getId());
            }
            if (deadLetter.getId() != null) {
                deadLetterIds.add(deadLetter.getId());
            }
            items.add(toDeadLetterFact(deadLetter, requestId));
        }

        List<IncidentDeadLetterFacts.DuplicateGroup> duplicateGroups = idsByBizKey.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> duplicateGroup(entry.getKey(), entry.getValue()))
                .toList();
        int duplicateRecordCount = idsByBizKey.values().stream()
                .mapToInt(ids -> Math.max(0, ids.size() - 1))
                .sum();

        TreeSet<String> missingRequestIds = new TreeSet<>(scope.requestIds());
        missingRequestIds.removeAll(requestIds);

        IncidentDeadLetterFacts facts = new IncidentDeadLetterFacts();
        facts.setRecordCount(deadLetters.size());
        facts.setTotalMatchingRecordCount(Math.toIntExact(totalMatching));
        facts.setDistinctBizKeyCount(bizKeys.size());
        facts.setDistinctRequestIdCount(requestIds.size());
        facts.setDuplicateRecordCount(duplicateRecordCount);
        facts.setUnmappedRecordCount(unmappedRecordCount);
        facts.setBizKeys(List.copyOf(bizKeys));
        facts.setRequestIds(List.copyOf(requestIds));
        facts.setDeadLetterIds(List.copyOf(deadLetterIds));
        facts.setDuplicateGroups(duplicateGroups);
        facts.setItems(List.copyOf(items));
        return response(
                scope,
                "dead-letter-facts",
                totalMatching > deadLetters.size(),
                List.copyOf(missingRequestIds),
                facts);
    }

    private OrderDependencyResult queryOrderService(List<String> requestIds) {
        try {
            OrderFactBatchRequest request = new OrderFactBatchRequest();
            request.setRequestIds(requestIds);
            ApiResponse<OrderFactBatchResult> response = orderClient.queryFacts(request);
            if (response == null || !Objects.equals(200, response.getCode()) || response.getData() == null) {
                return new OrderDependencyResult(false, List.of());
            }
            return new OrderDependencyResult(true, safeList(response.getData().getItems()));
        } catch (RuntimeException exception) {
            return new OrderDependencyResult(false, List.of());
        }
    }

    private IncidentOrderFacts.OrderFact toOrderFact(String requestId,
                                                      ReservationRequestEntity reservation,
                                                      OrderFactItemDto order,
                                                      boolean dependencyAvailable) {
        IncidentOrderFacts.OrderFact item = new IncidentOrderFacts.OrderFact();
        item.setRequestId(requestId);
        item.setReservationExists(reservation != null);
        item.setReservationStatus(reservation == null ? null : reservation.getStatus());
        item.setDependencyAvailable(dependencyAvailable);
        item.setOrderExists(order != null && Boolean.TRUE.equals(order.getExists()));
        item.setOrderNo(order != null ? order.getOrderNo() : reservation == null ? null : reservation.getOrderNo());
        item.setDeductNo(order != null ? order.getDeductNo() : null);
        item.setOrderStatus(order != null ? order.getStatus() : reservation == null ? null : reservation.getOrderStatus());
        item.setLatestEvent(reservation == null ? null : reservation.getLatestOrderEventType());
        item.setLatestEventTime(reservation == null ? null : reservation.getLatestOrderEventTime());
        item.setUpdatedAt(order != null ? order.getUpdatedAt() : reservation == null ? null : reservation.getUpdatedAt());
        return item;
    }

    private IncidentInventoryFacts.InventoryFact toInventoryFact(StockDeductRecordEntity deduct,
                                                                  StockItemEntity stockItem) {
        IncidentInventoryFacts.InventoryFact item = new IncidentInventoryFacts.InventoryFact();
        item.setRequestId(deduct.getRequestId());
        item.setDeductNo(deduct.getDeductNo());
        item.setDeductStatus(deduct.getStatus());
        item.setQuantity(deduct.getQuantity());
        item.setStockItemId(deduct.getStockItemId());
        item.setStockItemFound(stockItem != null);
        item.setUpdatedAt(deduct.getUpdatedAt());
        if (stockItem != null) {
            item.setTotalStock(stockItem.getTotalStock());
            item.setAvailableStock(stockItem.getAvailableStock());
            item.setLockedStock(stockItem.getLockedStock());
            item.setSoldStock(stockItem.getSoldStock());
            item.setInventoryInvariantOk(inventoryInvariantOk(stockItem));
        }
        return item;
    }

    private IncidentDeadLetterFacts.DeadLetterFact toDeadLetterFact(MqDeadLetterEntity entity,
                                                                    String requestId) {
        IncidentDeadLetterFacts.DeadLetterFact item = new IncidentDeadLetterFacts.DeadLetterFact();
        item.setDeadLetterId(entity.getId());
        item.setMessageId(entity.getMessageId());
        item.setDeadQueue(entity.getDeadQueue());
        item.setMessageType(entity.getMessageType());
        item.setBizKey(entity.getBizKey());
        item.setRequestId(requestId);
        item.setStatus(entity.getStatus());
        item.setReplayCount(entity.getReplayCount());
        item.setDeathReason(entity.getDeathReason());
        item.setCreatedAt(entity.getCreatedAt());
        item.setUpdatedAt(entity.getUpdatedAt());
        return item;
    }

    private IncidentDeadLetterFacts.DuplicateGroup duplicateGroup(String bizKey, List<Long> ids) {
        IncidentDeadLetterFacts.DuplicateGroup group = new IncidentDeadLetterFacts.DuplicateGroup();
        group.setBizKey(bizKey);
        group.setRecordCount(ids.size());
        group.setDeadLetterIds(ids.stream().filter(Objects::nonNull).sorted().toList());
        return group;
    }

    private IncidentFactResponse<IncidentDeadLetterFacts> emptyDeadLetterResponse(QueryScope scope) {
        IncidentDeadLetterFacts facts = new IncidentDeadLetterFacts();
        facts.setRecordCount(0);
        facts.setTotalMatchingRecordCount(0);
        facts.setDistinctBizKeyCount(0);
        facts.setDistinctRequestIdCount(0);
        facts.setDuplicateRecordCount(0);
        facts.setUnmappedRecordCount(0);
        facts.setBizKeys(List.of());
        facts.setRequestIds(List.of());
        facts.setDeadLetterIds(List.of());
        facts.setDuplicateGroups(List.of());
        facts.setItems(List.of());
        return response(scope, "dead-letter-facts", false, scope.requestIds(), facts);
    }

    private <T> IncidentFactResponse<T> response(QueryScope scope,
                                                 String factType,
                                                 boolean truncated,
                                                 List<String> missingRequestIds,
                                                 T facts) {
        IncidentFactResponse<T> response = new IncidentFactResponse<>();
        response.setSchemaVersion(SCHEMA_VERSION);
        response.setSourceSystem(SOURCE_SYSTEM);
        response.setSourceReference("incident/" + factType + "/" + scope.incidentId());
        response.setScopeHash(scope.scopeHash());
        response.setObservedAt(OffsetDateTime.now());
        response.setTruncated(truncated);
        response.setMissingRequestIds(missingRequestIds);
        response.setFacts(facts);
        return response;
    }

    private QueryScope normalize(IncidentFactQueryRequest request, boolean queuesRequired) {
        if (request == null
                || !StringUtils.hasText(request.getIncidentId())
                || !StringUtils.hasText(request.getSnapshotId())
                || !StringUtils.hasText(request.getScopeHash())) {
            throw new BizException("incidentId, snapshotId and scopeHash are required");
        }
        TreeSet<String> requestIds = normalizeStrings(request.getRequestIds(), "requestIds");
        if (requestIds.isEmpty() || requestIds.size() > MAX_REQUEST_IDS) {
            throw new BizException("requestIds size must be between 1 and " + MAX_REQUEST_IDS);
        }
        TreeSet<String> queueNames = normalizeStrings(request.getQueueNames(), "queueNames");
        if (queuesRequired && (queueNames.isEmpty() || queueNames.size() > 20)) {
            throw new BizException("queueNames size must be between 1 and 20");
        }
        int maxRecords = request.getMaxRecords() == null ? DEFAULT_MAX_RECORDS : request.getMaxRecords();
        if (maxRecords < 1 || maxRecords > MAX_RECORDS) {
            throw new BizException("maxRecords must be between 1 and " + MAX_RECORDS);
        }
        return new QueryScope(
                request.getIncidentId().trim(),
                request.getSnapshotId().trim(),
                request.getScopeHash().trim(),
                List.copyOf(requestIds),
                List.copyOf(queueNames),
                maxRecords);
    }

    private TreeSet<String> normalizeStrings(List<String> values, String field) {
        TreeSet<String> normalized = new TreeSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                throw new BizException(field + " must not contain blank values");
            }
            normalized.add(value.trim());
        }
        return normalized;
    }

    private boolean inventoryInvariantOk(StockItemEntity stockItem) {
        if (stockItem.getTotalStock() == null
                || stockItem.getAvailableStock() == null
                || stockItem.getLockedStock() == null
                || stockItem.getSoldStock() == null) {
            return false;
        }
        return stockItem.getTotalStock()
                == stockItem.getAvailableStock() + stockItem.getLockedStock() + stockItem.getSoldStock();
    }

    private boolean isUnreleased(Integer status) {
        return !Objects.equals(status, StockDeductStatusEnum.RELEASED.getCode())
                && !Objects.equals(status, StockDeductStatusEnum.SOLD.getCode());
    }

    private boolean isTerminalOrderStatus(Integer status) {
        return Objects.equals(status, 30) || Objects.equals(status, 40);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record QueryScope(String incidentId,
                              String snapshotId,
                              String scopeHash,
                              List<String> requestIds,
                              List<String> queueNames,
                              int maxRecords) {
    }

    private record OrderDependencyResult(boolean available, List<OrderFactItemDto> items) {
    }
}
