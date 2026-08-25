package com.javaup.resource.incident.scope;

import com.javaup.dto.IncidentAnomalyType;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.incident.scope.dto.RelationQuality;
import com.javaup.resource.incident.scope.dto.ResourceScopeEnrichmentRequest;
import com.javaup.resource.incident.scope.service.impl.ResourceScopeEnrichmentServiceImpl;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceScopeEnrichmentServiceTest {

    @Mock
    private ReservationRequestMapper reservationMapper;
    @Mock
    private StockDeductRecordMapper deductMapper;
    @Mock
    private StockItemMapper stockMapper;
    @Mock
    private MqDeadLetterMapper deadLetterMapper;

    private ResourceScopeEnrichmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ResourceScopeEnrichmentServiceImpl(
                reservationMapper, deductMapper, stockMapper, deadLetterMapper, new ObjectMapper());
    }

    @Test
    void enrichesUnreleasedInventoryAndStrongDeadLetterRelation() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation()));
        when(deductMapper.selectList(any())).thenReturn(List.of(deduct()));
        when(stockMapper.selectList(any())).thenReturn(List.of(stock()));
        when(deadLetterMapper.selectList(any())).thenReturn(List.of(deadLetter("DEDUCT-1", "{}")));

        var response = service.enrich(request());

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getReleaseState()).isEqualTo("UNRELEASED");
            assertThat(item.getStockAvailable()).isEqualTo(9);
            assertThat(item.getStockLocked()).isEqualTo(1);
            assertThat(item.getRelationQuality()).isEqualTo(RelationQuality.STRONG);
            assertThat(item.getDeadLetters()).singleElement().satisfies(dead -> {
                assertThat(dead.getDeadQueue()).isEqualTo("floworder.order.state.dlq");
                assertThat(dead.getRelationQuality()).isEqualTo(RelationQuality.STRONG);
            });
        });
        assertThat(response.getQueueNames()).containsExactly("floworder.order.state.dlq");
    }

    @Test
    void payloadOnlyRelationIsWeakAndNeverPromotedToStrong() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation()));
        when(deductMapper.selectList(any())).thenReturn(List.of(deduct()));
        when(stockMapper.selectList(any())).thenReturn(List.of(stock()));
        when(deadLetterMapper.selectList(any())).thenReturn(List.of(
                deadLetter(null, "{\"data\":{\"deductNo\":\"DEDUCT-1\"}}")));

        var item = service.enrich(request()).getItems().get(0);

        assertThat(item.getRelationQuality()).isEqualTo(RelationQuality.WEAK);
        assertThat(item.getDeadLetters()).singleElement()
                .satisfies(dead -> assertThat(dead.getRelationQuality()).isEqualTo(RelationQuality.WEAK));
    }

    @Test
    void missingDeadLetterRelationDoesNotBlockOrderInventoryFacts() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(reservation()));
        when(deductMapper.selectList(any())).thenReturn(List.of(deduct()));
        when(stockMapper.selectList(any())).thenReturn(List.of(stock()));
        when(deadLetterMapper.selectList(any())).thenReturn(List.of());

        var response = service.enrich(request());

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getRelationQuality()).isEqualTo(RelationQuality.MISSING);
            assertThat(item.getCompleteness()).isEqualTo("COMPLETE");
        });
        assertThat(response.getQueueNames()).isEmpty();
    }

    @Test
    void explicitDeadLetterIdResolvesAuthoritativeDeductAndRequestScope() {
        ResourceScopeEnrichmentRequest request = new ResourceScopeEnrichmentRequest();
        request.setDiscoveryRequestId("discovery-dead-letter");
        request.setDeadLetterIds(List.of(3L));
        request.setAnomalyTypes(List.of(IncidentAnomalyType.DEAD_LETTER_PENDING));
        when(deadLetterMapper.selectList(any())).thenReturn(
                List.of(deadLetter("DEDUCT-1", "{}")),
                List.of(deadLetter("DEDUCT-1", "{}")));
        when(deductMapper.selectList(any())).thenReturn(List.of(deduct()));
        when(stockMapper.selectList(any())).thenReturn(List.of(stock()));

        var response = service.enrich(request);

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getRequestId()).isEqualTo("REQ-1");
            assertThat(item.getDeductNo()).isEqualTo("DEDUCT-1");
            assertThat(item.getDeadLetters()).singleElement()
                    .satisfies(dead -> assertThat(dead.getDeadLetterId()).isEqualTo(3L));
        });
    }

    @Test
    void enrichmentContractIsReadOnly() throws Exception {
        Method method = ResourceScopeEnrichmentServiceImpl.class
                .getMethod("enrich", ResourceScopeEnrichmentRequest.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private ResourceScopeEnrichmentRequest request() {
        ResourceScopeEnrichmentRequest request = new ResourceScopeEnrichmentRequest();
        request.setDiscoveryRequestId("discovery-1");
        request.setRequestIds(List.of("REQ-1"));
        request.setDeductNos(List.of("DEDUCT-1"));
        request.setAnomalyTypes(List.of(IncidentAnomalyType.ORDER_TIMEOUT_INVENTORY_UNRELEASED));
        return request;
    }

    private ReservationRequestEntity reservation() {
        ReservationRequestEntity entity = new ReservationRequestEntity();
        entity.setId(1L);
        entity.setRequestId("REQ-1");
        entity.setOrderNo("ORDER-1");
        entity.setStatus(20);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 23, 0));
        return entity;
    }

    private StockDeductRecordEntity deduct() {
        StockDeductRecordEntity entity = new StockDeductRecordEntity();
        entity.setId(2L);
        entity.setRequestId("REQ-1");
        entity.setOrderNo("ORDER-1");
        entity.setDeductNo("DEDUCT-1");
        entity.setStockItemId(10L);
        entity.setStatus(20);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 23, 1));
        return entity;
    }

    private StockItemEntity stock() {
        StockItemEntity entity = new StockItemEntity();
        entity.setId(10L);
        entity.setAvailableStock(9);
        entity.setLockedStock(1);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 23, 2));
        return entity;
    }

    private MqDeadLetterEntity deadLetter(String bizKey, String content) {
        MqDeadLetterEntity entity = new MqDeadLetterEntity();
        entity.setId(3L);
        entity.setMessageId("MSG-1");
        entity.setBizKey(bizKey);
        entity.setContent(content);
        entity.setDeadQueue("floworder.order.state.dlq");
        entity.setExchangeName("floworder.order.state.exchange");
        entity.setRoutingKey("order.state.changed");
        entity.setMessageType("ORDER_TIMEOUT");
        entity.setStatus(0);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 23, 3));
        return entity;
    }
}
