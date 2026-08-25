package com.javaup.resource;

import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.service.ReservationRequestProcessor;
import com.javaup.resource.service.ReservationRequestService;
import com.javaup.resource.service.ResourceOrderService;
import com.javaup.resource.service.V8ReadValidationService;
import com.javaup.resource.service.InstantReservationProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static com.javaup.trace.TraceConstant.REQUEST_ID;
import static com.javaup.trace.TraceConstant.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationRequestProcessorTest {

    @Mock
    private ResourceOrderService resourceOrderService;

    @Mock
    private ReservationRequestService requestService;

    @Mock
    private V8ReadValidationService readValidationService;

    @Mock
    private InstantReservationProcessor instantReservationProcessor;

    private ReservationRequestProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReservationRequestProcessor(
                resourceOrderService,
                requestService,
                3,
                2,
                readValidationService,
                instantReservationProcessor
        );
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successShouldMarkAcceptedAndClearMdc() {
        ReservationRequestEntity request = request(0);
        when(resourceOrderService.createV3(any())).thenReturn("order-1");

        processor.process(request, "owner-1");

        verify(readValidationService).validate(any());
        verify(requestService).markAccepted(1L, "owner-1", "order-1");
        verify(requestService, never()).markFailed(anyLong(), anyString(), anyString());
        assertMdcCleared();
    }

    @Test
    void retryShouldSkipReadValidation() {
        ReservationRequestEntity request = request(1);
        when(resourceOrderService.createV3(any())).thenReturn("order-1");

        processor.process(request, "owner-1");

        verify(readValidationService, never()).validate(any());
        verify(requestService).markAccepted(1L, "owner-1", "order-1");
        assertMdcCleared();
    }

    @Test
    void businessFailureShouldMarkFailedAndClearMdc() {
        ReservationRequestEntity request = request(0);
        when(resourceOrderService.createV3(any()))
                .thenThrow(new BizException("额度不足"));

        processor.process(request, "owner-1");

        verify(requestService).markFailed(1L, "owner-1", "额度不足");
        verify(requestService, never()).markRetry(anyLong(), anyString(), any(), anyString());
        assertMdcCleared();
    }

    @Test
    void technicalFailureShouldScheduleRetryBeforeLimit() {
        ReservationRequestEntity request = request(1);
        when(resourceOrderService.createV3(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        processor.process(request, "owner-1");

        verify(requestService).markRetry(
                eq(1L),
                eq("owner-1"),
                any(),
                eq("database unavailable")
        );
        verify(requestService, never())
                .markManualReview(anyLong(), anyString(), anyString());
        assertMdcCleared();
    }

    @Test
    void technicalFailureShouldEnterManualReviewAtLimit() {
        ReservationRequestEntity request = request(2);
        when(resourceOrderService.createV3(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        processor.process(request, "owner-1");

        verify(requestService).markManualReview(
                1L,
                "owner-1",
                "database unavailable"
        );
        verify(requestService, never()).markRetry(anyLong(), anyString(), any(), anyString());
        assertMdcCleared();
    }

    private ReservationRequestEntity request(int retryCount) {
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setId(1L);
        request.setRequestId("request-1");
        request.setTraceId("trace-1");
        request.setUserId(1001L);
        request.setResourceId(1L);
        request.setStockItemId(1L);
        request.setQuantity(1);
        request.setRetryCount(retryCount);
        return request;
    }

    private void assertMdcCleared() {
        assertNull(MDC.get(TRACE_ID));
        assertNull(MDC.get(REQUEST_ID));
    }
}
