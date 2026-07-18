package com.javaup.resource.incident;

import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderFactBatchResult;
import com.javaup.dto.OrderFactItemDto;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.incident.dto.IncidentDeadLetterFacts;
import com.javaup.resource.incident.dto.IncidentFactQueryRequest;
import com.javaup.resource.incident.dto.IncidentFactResponse;
import com.javaup.resource.incident.dto.IncidentOrderFacts;
import com.javaup.resource.incident.service.impl.IncidentFactQueryServiceImpl;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentFactQueryServiceImplTest {

    @Mock
    private ReservationRequestMapper reservationRequestMapper;
    @Mock
    private StockDeductRecordMapper stockDeductRecordMapper;
    @Mock
    private StockItemMapper stockItemMapper;
    @Mock
    private MqDeadLetterMapper mqDeadLetterMapper;
    @Mock
    private OrderClient orderClient;

    private IncidentFactQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IncidentFactQueryServiceImpl(
                reservationRequestMapper,
                stockDeductRecordMapper,
                stockItemMapper,
                mqDeadLetterMapper,
                orderClient);
    }

    @Test
    void deadLetterFactsKeepPhysicalAndBusinessCountsSeparate() {
        when(stockDeductRecordMapper.selectList(any())).thenReturn(List.of(
                deduct("REQ-2", "DEDUCT-2", 2L),
                deduct("REQ-1", "DEDUCT-1", 1L)));
        when(mqDeadLetterMapper.selectCount(any())).thenReturn(3L);
        when(mqDeadLetterMapper.selectList(any())).thenReturn(List.of(
                deadLetter(11L, "DEDUCT-1"),
                deadLetter(12L, "DEDUCT-1"),
                deadLetter(13L, "DEDUCT-2")));

        IncidentFactResponse<IncidentDeadLetterFacts> response = service.queryDeadLetters(
                request(List.of("REQ-2", "REQ-1"), List.of("orders.dlq")));

        IncidentDeadLetterFacts facts = response.getFacts();
        assertThat(facts.getRecordCount()).isEqualTo(3);
        assertThat(facts.getDistinctBizKeyCount()).isEqualTo(2);
        assertThat(facts.getDistinctRequestIdCount()).isEqualTo(2);
        assertThat(facts.getDuplicateRecordCount()).isEqualTo(1);
        assertThat(facts.getBizKeys()).containsExactly("DEDUCT-1", "DEDUCT-2");
        assertThat(facts.getRequestIds()).containsExactly("REQ-1", "REQ-2");
        assertThat(facts.getDeadLetterIds()).containsExactly(11L, 12L, 13L);
        assertThat(facts.getDuplicateGroups()).singleElement()
                .satisfies(group -> {
                    assertThat(group.getBizKey()).isEqualTo("DEDUCT-1");
                    assertThat(group.getRecordCount()).isEqualTo(2);
                });
        assertThat(response.getTruncated()).isFalse();
    }

    @Test
    void orderFactsExposeDependencyFailureWithoutInventingMissingOrders() {
        ReservationRequestEntity reservation = new ReservationRequestEntity();
        reservation.setRequestId("REQ-1");
        reservation.setStatus(20);
        reservation.setOrderStatus(40);
        when(reservationRequestMapper.selectList(any())).thenReturn(List.of(reservation));
        when(orderClient.queryFacts(any())).thenThrow(new IllegalStateException("order service timeout"));

        IncidentFactResponse<IncidentOrderFacts> response = service.queryOrders(
                request(List.of("REQ-1"), List.of()));

        assertThat(response.getFacts().getRecordCount()).isZero();
        assertThat(response.getMissingRequestIds()).isEmpty();
        assertThat(response.getFacts().getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getDependencyAvailable()).isFalse();
                    assertThat(item.getReservationExists()).isTrue();
                    assertThat(item.getOrderExists()).isFalse();
                });
    }

    @Test
    void orderFactsUseStableRequestIdOrderingAndTerminalSet() {
        when(reservationRequestMapper.selectList(any())).thenReturn(List.of());
        OrderFactBatchResult batch = new OrderFactBatchResult();
        batch.setItems(List.of(
                order("REQ-1", 40),
                order("REQ-2", 20)));
        when(orderClient.queryFacts(any())).thenReturn(ApiResponse.success(batch));

        IncidentFactResponse<IncidentOrderFacts> response = service.queryOrders(
                request(List.of("REQ-2", "REQ-1"), List.of()));

        assertThat(response.getFacts().getRequestIds()).containsExactly("REQ-1", "REQ-2");
        assertThat(response.getFacts().getTerminalRequestIds()).containsExactly("REQ-1");
        assertThat(response.getFacts().getTerminalDistinctRequestIdCount()).isEqualTo(1);
    }

    private IncidentFactQueryRequest request(List<String> requestIds, List<String> queues) {
        IncidentFactQueryRequest request = new IncidentFactQueryRequest();
        request.setIncidentId("inc-1");
        request.setSnapshotId("snap-1");
        request.setScopeHash("scope-hash");
        request.setRequestIds(requestIds);
        request.setQueueNames(queues);
        request.setMaxRecords(500);
        return request;
    }

    private StockDeductRecordEntity deduct(String requestId, String deductNo, long stockItemId) {
        StockDeductRecordEntity deduct = new StockDeductRecordEntity();
        deduct.setRequestId(requestId);
        deduct.setDeductNo(deductNo);
        deduct.setStockItemId(stockItemId);
        return deduct;
    }

    private MqDeadLetterEntity deadLetter(long id, String bizKey) {
        MqDeadLetterEntity deadLetter = new MqDeadLetterEntity();
        deadLetter.setId(id);
        deadLetter.setBizKey(bizKey);
        deadLetter.setDeadQueue("orders.dlq");
        deadLetter.setStatus(0);
        return deadLetter;
    }

    private OrderFactItemDto order(String requestId, int status) {
        OrderFactItemDto item = new OrderFactItemDto();
        item.setRequestId(requestId);
        item.setExists(true);
        item.setOrderNo("ORDER-" + requestId);
        item.setStatus(status);
        return item;
    }
}
