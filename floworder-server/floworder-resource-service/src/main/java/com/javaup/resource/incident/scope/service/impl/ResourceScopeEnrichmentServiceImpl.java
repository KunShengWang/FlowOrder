package com.javaup.resource.incident.scope.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.IncidentAnomalyType;
import com.javaup.dto.IncidentSourceReference;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.incident.scope.dto.RelationQuality;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentRequest;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentResponse;
import com.javaup.resource.incident.scope.service.ResourceScopeEnrichmentService;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
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
public class ResourceScopeEnrichmentServiceImpl implements ResourceScopeEnrichmentService {

    private static final int MAX_IDENTIFIERS = 100;
    private static final String SOURCE_SYSTEM = "floworder-resource-service";

    private final ReservationRequestMapper reservationRequestMapper;
    private final StockDeductRecordMapper stockDeductRecordMapper;
    private final StockItemMapper stockItemMapper;
    private final MqDeadLetterMapper deadLetterMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public ResourceScopeEnrichmentResponse enrich(ResourceScopeEnrichmentRequest request) {
        Scope scope = normalize(request);
        Map<String, ReservationRequestEntity> reservations = loadReservations(scope.requestIds());
        Map<String, StockDeductRecordEntity> deducts = loadDeducts(scope.requestIds(), scope.deductNos());
        Map<Long, StockItemEntity> stockItems = loadStockItems(deducts.values());
        Map<String, List<MqDeadLetterEntity>> deadLetters = loadDeadLetters(deducts.values(), scope.deductNos());

        TreeSet<String> itemKeys = new TreeSet<>();
        itemKeys.addAll(scope.requestIds().stream().map(value -> "R|" + value).toList());
        itemKeys.addAll(scope.deductNos().stream().map(value -> "D|" + value).toList());
        for (StockDeductRecordEntity deduct : deducts.values()) {
            itemKeys.add("R|" + deduct.getRequestId());
        }

        List<ResourceScopeEnrichmentResponse.Item> items = new ArrayList<>();
        TreeSet<String> queues = new TreeSet<>();
        for (String key : itemKeys) {
            String value = key.substring(2);
            StockDeductRecordEntity deduct = key.startsWith("R|")
                    ? deducts.get("R|" + value)
                    : deducts.get("D|" + value);
            String requestId = deduct == null && key.startsWith("R|") ? value : deduct == null ? "" : deduct.getRequestId();
            ReservationRequestEntity reservation = reservations.get(requestId);
            List<MqDeadLetterEntity> related = deduct == null
                    ? List.of()
                    : deadLetters.getOrDefault(deduct.getDeductNo(), List.of());
            ResourceScopeEnrichmentResponse.Item item = item(
                    requestId,
                    deduct == null && key.startsWith("D|") ? value : null,
                    reservation,
                    deduct,
                    stockItems,
                    related,
                    scope.anomalyTypes(),
                    queues);
            if (items.stream().noneMatch(existing -> sameIdentity(existing, item))) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(ResourceScopeEnrichmentResponse.Item::getRequestId,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ResourceScopeEnrichmentResponse.Item::getDeductNo,
                        Comparator.nullsLast(String::compareTo)));

        ResourceScopeEnrichmentResponse response = new ResourceScopeEnrichmentResponse();
        response.setDiscoveryRequestId(scope.discoveryRequestId());
        response.setObservedAt(LocalDateTime.now());
        response.setItems(List.copyOf(items));
        response.setQueueNames(List.copyOf(queues));
        response.setSourceHealth(Map.of(
                "reservation", "AVAILABLE",
                "inventory", "AVAILABLE",
                "deadLetter", "AVAILABLE"));
        return response;
    }

    private Map<String, ReservationRequestEntity> loadReservations(List<String> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        return reservationRequestMapper.selectList(Wrappers.<ReservationRequestEntity>lambdaQuery()
                        .in(ReservationRequestEntity::getRequestId, requestIds)
                        .orderByAsc(ReservationRequestEntity::getRequestId))
                .stream()
                .collect(Collectors.toMap(
                        ReservationRequestEntity::getRequestId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private Map<String, StockDeductRecordEntity> loadDeducts(List<String> requestIds,
                                                              List<String> deductNos) {
        TreeMap<String, StockDeductRecordEntity> result = new TreeMap<>();
        if (!requestIds.isEmpty()) {
            for (StockDeductRecordEntity deduct : stockDeductRecordMapper.selectList(
                    Wrappers.<StockDeductRecordEntity>lambdaQuery()
                            .in(StockDeductRecordEntity::getRequestId, requestIds)
                            .orderByAsc(StockDeductRecordEntity::getRequestId))) {
                result.putIfAbsent("R|" + deduct.getRequestId(), deduct);
                result.putIfAbsent("D|" + deduct.getDeductNo(), deduct);
            }
        }
        if (!deductNos.isEmpty()) {
            for (StockDeductRecordEntity deduct : stockDeductRecordMapper.selectList(
                    Wrappers.<StockDeductRecordEntity>lambdaQuery()
                            .in(StockDeductRecordEntity::getDeductNo, deductNos)
                            .orderByAsc(StockDeductRecordEntity::getDeductNo))) {
                result.putIfAbsent("R|" + deduct.getRequestId(), deduct);
                result.putIfAbsent("D|" + deduct.getDeductNo(), deduct);
            }
        }
        return result;
    }

    private Map<Long, StockItemEntity> loadStockItems(Iterable<StockDeductRecordEntity> deducts) {
        TreeSet<Long> stockItemIds = new TreeSet<>();
        deducts.forEach(deduct -> {
            if (deduct.getStockItemId() != null) {
                stockItemIds.add(deduct.getStockItemId());
            }
        });
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        return stockItemMapper.selectList(Wrappers.<StockItemEntity>lambdaQuery()
                        .in(StockItemEntity::getId, stockItemIds)
                        .orderByAsc(StockItemEntity::getId))
                .stream()
                .collect(Collectors.toMap(StockItemEntity::getId, Function.identity()));
    }

    private Map<String, List<MqDeadLetterEntity>> loadDeadLetters(
            Iterable<StockDeductRecordEntity> deducts,
            List<String> explicitDeductNos) {
        TreeSet<String> knownDeductNos = new TreeSet<>(explicitDeductNos);
        deducts.forEach(deduct -> {
            if (StringUtils.hasText(deduct.getDeductNo())) {
                knownDeductNos.add(deduct.getDeductNo());
            }
        });
        if (knownDeductNos.isEmpty()) {
            return Map.of();
        }
        List<MqDeadLetterEntity> rows = deadLetterMapper.selectList(Wrappers.<MqDeadLetterEntity>lambdaQuery()
                .in(MqDeadLetterEntity::getBizKey, knownDeductNos)
                .orderByAsc(MqDeadLetterEntity::getBizKey)
                .orderByAsc(MqDeadLetterEntity::getId));
        Map<String, List<MqDeadLetterEntity>> result = new TreeMap<>();
        for (MqDeadLetterEntity row : rows) {
            if (StringUtils.hasText(row.getBizKey()) && knownDeductNos.contains(row.getBizKey())) {
                result.computeIfAbsent(row.getBizKey(), ignored -> new ArrayList<>()).add(row);
                continue;
            }
            List<String> payloadMatches = knownDeductNos.stream()
                    .filter(deductNo -> payloadMentions(row.getContent(), deductNo))
                    .toList();
            if (payloadMatches.size() == 1) {
                result.computeIfAbsent(payloadMatches.get(0), ignored -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    private ResourceScopeEnrichmentResponse.Item item(
            String requestId,
            String fallbackDeductNo,
            ReservationRequestEntity reservation,
            StockDeductRecordEntity deduct,
            Map<Long, StockItemEntity> stockItems,
            List<MqDeadLetterEntity> deadLetters,
            List<IncidentAnomalyType> anomalyTypes,
            Set<String> queues) {
        ResourceScopeEnrichmentResponse.Item item = new ResourceScopeEnrichmentResponse.Item();
        item.setRequestId(requestId);
        item.setOrderNo(deduct != null ? deduct.getOrderNo() : reservation == null ? null : reservation.getOrderNo());
        item.setDeductNo(deduct == null ? fallbackDeductNo : deduct.getDeductNo());
        item.setReservationStatus(reservation == null ? null : reservation.getStatus());
        item.setDeductStatus(deduct == null ? null : deduct.getStatus());
        item.setReleaseState(releaseState(deduct));
        item.setStockItemId(deduct == null ? null : deduct.getStockItemId());
        StockItemEntity stock = deduct == null ? null : stockItems.get(deduct.getStockItemId());
        item.setStockAvailable(stock == null ? null : stock.getAvailableStock());
        item.setStockLocked(stock == null ? null : stock.getLockedStock());
        item.setAnomalyTypes(List.copyOf(anomalyTypes));

        List<ResourceScopeEnrichmentResponse.DeadLetter> facts = deadLetters.stream()
                .map(row -> deadLetter(row, item.getDeductNo()))
                .toList();
        facts.stream().map(ResourceScopeEnrichmentResponse.DeadLetter::getDeadQueue)
                .filter(StringUtils::hasText).forEach(queues::add);
        item.setDeadLetters(facts);
        item.setRelationQuality(facts.isEmpty()
                ? RelationQuality.MISSING
                : facts.stream().allMatch(fact -> fact.getRelationQuality() == RelationQuality.STRONG)
                ? RelationQuality.STRONG : RelationQuality.WEAK);
        item.setCompleteness(deduct == null ? "MISSING_DEDUCT"
                : stock == null ? "MISSING_STOCK_FACT"
                : "COMPLETE");

        List<IncidentSourceReference> references = new ArrayList<>();
        if (reservation != null) {
            references.add(reference("fo_reservation_request", reservation.getId(), reservation.getUpdatedAt()));
        }
        if (deduct != null) {
            references.add(reference("fo_stock_deduct_record", deduct.getId(), deduct.getUpdatedAt()));
        }
        if (stock != null) {
            references.add(reference("fo_stock_item", stock.getId(), stock.getUpdatedAt()));
        }
        item.setSourceReferences(List.copyOf(references));
        return item;
    }

    private ResourceScopeEnrichmentResponse.DeadLetter deadLetter(MqDeadLetterEntity row,
                                                                   String deductNo) {
        ResourceScopeEnrichmentResponse.DeadLetter fact = new ResourceScopeEnrichmentResponse.DeadLetter();
        fact.setDeadLetterId(row.getId());
        fact.setMessageId(row.getMessageId());
        fact.setDeadQueue(row.getDeadQueue());
        fact.setExchange(row.getExchangeName());
        fact.setRoutingKey(row.getRoutingKey());
        fact.setMessageType(row.getMessageType());
        fact.setStatus(row.getStatus());
        fact.setObservedAt(row.getUpdatedAt());
        fact.setRelationQuality(Objects.equals(row.getBizKey(), deductNo)
                ? RelationQuality.STRONG
                : payloadMentions(row.getContent(), deductNo) ? RelationQuality.WEAK : RelationQuality.MISSING);
        fact.setSourceReferences(List.of(reference("fo_mq_dead_letter", row.getId(), row.getUpdatedAt())));
        return fact;
    }

    @SuppressWarnings("unchecked")
    private boolean payloadMentions(String content, String expectedValue) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(expectedValue)) {
            return false;
        }
        try {
            return containsValue(objectMapper.readValue(content, Map.class), expectedValue);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean containsValue(Object value, String expected) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(child -> containsValue(child, expected));
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                if (containsValue(child, expected)) {
                    return true;
                }
            }
        }
        return Objects.equals(expected, value);
    }

    private boolean sameIdentity(ResourceScopeEnrichmentResponse.Item left,
                                 ResourceScopeEnrichmentResponse.Item right) {
        return Objects.equals(left.getRequestId(), right.getRequestId())
                && Objects.equals(left.getDeductNo(), right.getDeductNo());
    }

    private String releaseState(StockDeductRecordEntity deduct) {
        if (deduct == null || deduct.getStatus() == null) {
            return "UNKNOWN";
        }
        return switch (deduct.getStatus()) {
            case 30 -> "RELEASED";
            case 60 -> "SOLD";
            default -> "UNRELEASED";
        };
    }

    private IncidentSourceReference reference(String type, Long id, LocalDateTime observedAt) {
        return new IncidentSourceReference(SOURCE_SYSTEM, type, id == null ? "" : String.valueOf(id), observedAt);
    }

    private Scope normalize(ResourceScopeEnrichmentRequest request) {
        if (request == null || !StringUtils.hasText(request.getDiscoveryRequestId())) {
            throw new BizException("discoveryRequestId must not be blank");
        }
        List<String> requestIds = normalizeStrings(request.getRequestIds(), "requestIds");
        List<String> deductNos = normalizeStrings(request.getDeductNos(), "deductNos");
        if (requestIds.isEmpty() && deductNos.isEmpty()) {
            throw new BizException("requestIds or deductNos must not be empty");
        }
        if (requestIds.size() > MAX_IDENTIFIERS || deductNos.size() > MAX_IDENTIFIERS) {
            throw new BizException("identifier count must not exceed " + MAX_IDENTIFIERS);
        }
        TreeSet<IncidentAnomalyType> anomalyTypes = new TreeSet<>(Comparator.comparing(Enum::name));
        if (request.getAnomalyTypes() != null) {
            anomalyTypes.addAll(request.getAnomalyTypes());
        }
        if (anomalyTypes.isEmpty()) {
            throw new BizException("anomalyTypes must not be empty");
        }
        return new Scope(request.getDiscoveryRequestId().trim(), requestIds, deductNos,
                List.copyOf(anomalyTypes));
    }

    private List<String> normalizeStrings(List<String> values, String field) {
        TreeSet<String> result = new TreeSet<>();
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                throw new BizException(field + " must not contain blank values");
            }
            result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private record Scope(String discoveryRequestId,
                         List<String> requestIds,
                         List<String> deductNos,
                         List<IncidentAnomalyType> anomalyTypes) {
    }
}
