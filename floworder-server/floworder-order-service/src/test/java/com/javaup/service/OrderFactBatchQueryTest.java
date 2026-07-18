package com.javaup.service;

import com.javaup.dto.OrderFactBatchRequest;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderFactBatchQueryTest {

    @Mock
    private ReservationOrderMapper orderMapper;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
    }

    @Test
    void returnsOneStableItemPerRequestedId() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("REQ-2", "ORDER-2", 20),
                order("REQ-1", "ORDER-1", 40)));
        OrderFactBatchRequest request = new OrderFactBatchRequest();
        request.setRequestIds(List.of("REQ-2", "REQ-1", "REQ-3", "REQ-1"));

        var result = service.queryFacts(request);

        assertThat(result.getItems()).extracting(item -> item.getRequestId())
                .containsExactly("REQ-1", "REQ-2", "REQ-3");
        assertThat(result.getItems()).extracting(item -> item.getExists())
                .containsExactly(true, true, false);
        assertThat(result.getMissingRequestIds()).containsExactly("REQ-3");
    }

    @Test
    void rejectsMoreThanOneHundredDistinctIdsBeforeDatabaseAccess() {
        OrderFactBatchRequest request = new OrderFactBatchRequest();
        request.setRequestIds(IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "REQ-" + index)
                .toList());

        assertThatThrownBy(() -> service.queryFacts(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("between 1 and 100");
        verifyNoInteractions(orderMapper);
    }

    private ReservationOrderEntity order(String requestId, String orderNo, int status) {
        ReservationOrderEntity entity = new ReservationOrderEntity();
        entity.setRequestId(requestId);
        entity.setOrderNo(orderNo);
        entity.setDeductNo("DEDUCT-" + requestId);
        entity.setStatus(status);
        return entity;
    }
}
