package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.javaup.dto.IncidentAnomalyType;
import com.javaup.dto.IncidentSourceReference;
import com.javaup.dto.OrderScopeCandidateItem;
import com.javaup.dto.OrderScopeCandidateRequest;
import com.javaup.dto.OrderScopeCandidateResponse;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.IncidentOrderScopeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class IncidentOrderScopeQueryServiceImpl implements IncidentOrderScopeQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Duration MAX_WINDOW = Duration.ofHours(24);
    private static final String SOURCE_SYSTEM = "floworder-order-service";

    private final ReservationOrderMapper orderMapper;

    @Override
    @Transactional(readOnly = true)
    public OrderScopeCandidateResponse discover(OrderScopeCandidateRequest request) {
        Scope scope = normalize(request);
        LambdaQueryWrapper<ReservationOrderEntity> query = new LambdaQueryWrapper<ReservationOrderEntity>()
                .eq(ReservationOrderEntity::getDeleted, 0);
        if (!scope.orderNos().isEmpty()) {
            query.in(ReservationOrderEntity::getOrderNo, scope.orderNos());
        }
        if (scope.startTime() != null) {
            query.ge(ReservationOrderEntity::getUpdatedAt, scope.startTime())
                    .lt(ReservationOrderEntity::getUpdatedAt, scope.endTime());
        }
        Set<Integer> statuses = statusFilter(scope.anomalyTypes());
        if (!statuses.isEmpty()) {
            query.in(ReservationOrderEntity::getStatus, statuses);
        }
        if (scope.cursor() != null) {
            query.and(nested -> nested
                    .gt(ReservationOrderEntity::getUpdatedAt, scope.cursor().observedAt())
                    .or(group -> group
                            .eq(ReservationOrderEntity::getUpdatedAt, scope.cursor().observedAt())
                            .gt(ReservationOrderEntity::getId, scope.cursor().id())));
        }
        query.orderByAsc(ReservationOrderEntity::getUpdatedAt)
                .orderByAsc(ReservationOrderEntity::getId)
                .last("LIMIT " + (scope.limit() + 1));

        List<ReservationOrderEntity> rows = orderMapper.selectList(query);
        boolean truncated = rows.size() > scope.limit();
        List<ReservationOrderEntity> selected = truncated
                ? rows.subList(0, scope.limit())
                : rows;
        List<OrderScopeCandidateItem> candidates = selected.stream()
                .map(row -> candidate(row, scope.anomalyTypes()))
                .toList();

        OrderScopeCandidateResponse response = new OrderScopeCandidateResponse();
        response.setDiscoveryRequestId(scope.discoveryRequestId());
        response.setObservedAt(LocalDateTime.now());
        response.setCandidates(candidates);
        response.setCandidateCount(candidates.size());
        response.setTruncated(truncated);
        response.setNextCursor(truncated ? encodeCursor(selected.get(selected.size() - 1)) : "");
        return response;
    }

    private Scope normalize(OrderScopeCandidateRequest request) {
        if (request == null || !StringUtils.hasText(request.getDiscoveryRequestId())) {
            throw new BizException("discoveryRequestId must not be blank");
        }
        EnumSet<IncidentAnomalyType> anomalyTypes = EnumSet.noneOf(IncidentAnomalyType.class);
        if (request.getAnomalyTypes() != null) {
            anomalyTypes.addAll(request.getAnomalyTypes());
        }
        if (anomalyTypes.isEmpty()) {
            throw new BizException("anomalyTypes must not be empty");
        }
        TreeSet<String> orderNos = new TreeSet<>();
        if (request.getExplicitOrderNos() != null) {
            for (String orderNo : request.getExplicitOrderNos()) {
                if (!StringUtils.hasText(orderNo)) {
                    throw new BizException("explicitOrderNos must not contain blank values");
                }
                orderNos.add(orderNo.trim());
            }
        }
        if (orderNos.size() > MAX_LIMIT) {
            throw new BizException("explicitOrderNos size must not exceed " + MAX_LIMIT);
        }
        LocalDateTime start = request.getStartTime();
        LocalDateTime end = request.getEndTime();
        if ((start == null) != (end == null)) {
            throw new BizException("startTime and endTime must be provided together");
        }
        if (start != null && (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0)) {
            throw new BizException("time range must be positive and not exceed 24 hours");
        }
        if (start == null && orderNos.isEmpty()) {
            throw new BizException("a time range or explicit orderNo is required");
        }
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BizException("limit must be between 1 and " + MAX_LIMIT);
        }
        return new Scope(
                request.getDiscoveryRequestId().trim(),
                start,
                end,
                List.copyOf(anomalyTypes),
                List.copyOf(orderNos),
                limit,
                decodeCursor(request.getCursor()));
    }

    private Set<Integer> statusFilter(List<IncidentAnomalyType> anomalyTypes) {
        boolean broad = anomalyTypes.contains(IncidentAnomalyType.DEAD_LETTER_PENDING)
                || anomalyTypes.contains(IncidentAnomalyType.ORDER_INVENTORY_STATE_MISMATCH);
        if (broad) {
            return Set.of();
        }
        TreeSet<Integer> statuses = new TreeSet<>();
        if (anomalyTypes.contains(IncidentAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED)) {
            statuses.add(40);
        }
        if (anomalyTypes.contains(IncidentAnomalyType.ORDER_CANCELLED_INVENTORY_UNRELEASED)) {
            statuses.add(30);
        }
        return statuses;
    }

    private OrderScopeCandidateItem candidate(ReservationOrderEntity order,
                                               List<IncidentAnomalyType> requestedTypes) {
        OrderScopeCandidateItem item = new OrderScopeCandidateItem();
        item.setRequestId(order.getRequestId());
        item.setOrderNo(order.getOrderNo());
        item.setDeductNo(order.getDeductNo());
        item.setOrderStatus(order.getStatus());
        // Reservation request is resource-service owned and is enriched by the second read-only endpoint.
        item.setReservationStatus(null);
        item.setObservedAt(order.getUpdatedAt());
        item.setAnomalyTypes(applicableTypes(order.getStatus(), requestedTypes));
        item.setSourceReferences(List.of(new IncidentSourceReference(
                SOURCE_SYSTEM,
                "fo_reservation_order",
                String.valueOf(order.getId()),
                order.getUpdatedAt())));
        return item;
    }

    private List<IncidentAnomalyType> applicableTypes(Integer status,
                                                       List<IncidentAnomalyType> requestedTypes) {
        List<IncidentAnomalyType> result = new ArrayList<>();
        for (IncidentAnomalyType type : requestedTypes) {
            if (type == IncidentAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED && Integer.valueOf(40).equals(status)
                    || type == IncidentAnomalyType.ORDER_CANCELLED_INVENTORY_UNRELEASED && Integer.valueOf(30).equals(status)
                    || type == IncidentAnomalyType.DEAD_LETTER_PENDING
                    || type == IncidentAnomalyType.ORDER_INVENTORY_STATE_MISMATCH) {
                result.add(type);
            }
        }
        return List.copyOf(result);
    }

    private String encodeCursor(ReservationOrderEntity row) {
        String raw = row.getUpdatedAt() + "|" + row.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded) {
        if (!StringUtils.hasText(encoded)) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("invalid cursor shape");
            }
            return new Cursor(LocalDateTime.parse(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new BizException("cursor is invalid");
        }
    }

    private record Scope(String discoveryRequestId,
                         LocalDateTime startTime,
                         LocalDateTime endTime,
                         List<IncidentAnomalyType> anomalyTypes,
                         List<String> orderNos,
                         int limit,
                         Cursor cursor) {
    }

    private record Cursor(LocalDateTime observedAt, long id) {
    }
}
