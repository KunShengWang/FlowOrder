package com.javaup.service;

import com.javaup.dto.IncidentAnomalyType;
import com.javaup.dto.OrderScopeCandidateRequest;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.impl.IncidentOrderScopeQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentOrderScopeQueryServiceTest {

    @Mock
    private ReservationOrderMapper orderMapper;

    private IncidentOrderScopeQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IncidentOrderScopeQueryServiceImpl(orderMapper);
    }

    @Test
    void discoversTimeoutCandidatesInStableOrder() {
        LocalDateTime observed = LocalDateTime.of(2026, 7, 20, 23, 15);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(1L, "REQ-1", "ORDER-1", "DEDUCT-1", 40, observed)));

        var response = service.discover(request(observed.minusHours(2), observed.plusHours(1), 50));

        assertThat(response.getCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getRequestId()).isEqualTo("REQ-1");
            assertThat(candidate.getOrderNo()).isEqualTo("ORDER-1");
            assertThat(candidate.getDeductNo()).isEqualTo("DEDUCT-1");
            assertThat(candidate.getOrderStatus()).isEqualTo(40);
            assertThat(candidate.getReservationStatus()).isNull();
            assertThat(candidate.getSourceReferences()).singleElement()
                    .satisfies(reference -> assertThat(reference.getSourceType())
                            .isEqualTo("fo_reservation_order"));
        });
        assertThat(response.getTruncated()).isFalse();
    }

    @Test
    void explicitOrderNoIsAValidAnchorWithoutTimeRange() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        OrderScopeCandidateRequest request = baseRequest();
        request.setExplicitOrderNos(List.of("ORDER-9"));

        assertThat(service.discover(request).getCandidates()).isEmpty();
    }

    @Test
    void returnsStableCursorWhenCandidatePageIsTruncated() {
        LocalDateTime observed = LocalDateTime.of(2026, 7, 20, 23, 15);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(1L, "REQ-1", "ORDER-1", "DEDUCT-1", 40, observed),
                order(2L, "REQ-2", "ORDER-2", "DEDUCT-2", 40, observed.plusMinutes(1))));
        OrderScopeCandidateRequest request = request(observed.minusHours(1), observed.plusHours(1), 1);

        var first = service.discover(request);
        request.setCursor(first.getNextCursor());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        var second = service.discover(request);

        assertThat(first.getCandidates()).hasSize(1);
        assertThat(first.getTruncated()).isTrue();
        assertThat(first.getNextCursor()).isNotBlank();
        assertThat(second.getCandidates()).isEmpty();
    }

    @Test
    void rejectsWindowLongerThanTwentyFourHoursBeforeQuery() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 20, 0, 0);
        assertThatThrownBy(() -> service.discover(request(start, start.plusHours(25), 50)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("24 hours");
    }

    @Test
    void discoveryContractIsReadOnly() throws Exception {
        Method method = IncidentOrderScopeQueryServiceImpl.class
                .getMethod("discover", OrderScopeCandidateRequest.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private OrderScopeCandidateRequest request(LocalDateTime start, LocalDateTime end, int limit) {
        OrderScopeCandidateRequest request = baseRequest();
        request.setStartTime(start);
        request.setEndTime(end);
        request.setLimit(limit);
        return request;
    }

    private OrderScopeCandidateRequest baseRequest() {
        OrderScopeCandidateRequest request = new OrderScopeCandidateRequest();
        request.setDiscoveryRequestId("discovery-1");
        request.setAnomalyTypes(List.of(IncidentAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED));
        return request;
    }

    private ReservationOrderEntity order(long id,
                                         String requestId,
                                         String orderNo,
                                         String deductNo,
                                         int status,
                                         LocalDateTime updatedAt) {
        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setId(id);
        order.setRequestId(requestId);
        order.setOrderNo(orderNo);
        order.setDeductNo(deductNo);
        order.setStatus(status);
        order.setUpdatedAt(updatedAt);
        return order;
    }
}
