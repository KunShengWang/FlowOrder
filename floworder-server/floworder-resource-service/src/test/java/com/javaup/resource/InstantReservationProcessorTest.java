package com.javaup.resource;

import com.javaup.dto.InstantReservationResultDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.exception.InstantStockMismatchException;
import com.javaup.resource.service.InstantAdmissionService;
import com.javaup.resource.service.InstantReservationProcessor;
import com.javaup.resource.service.ReservationRequestService;
import com.javaup.resource.service.ResourceOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstantReservationProcessorTest {

    @Mock
    private ResourceOrderService resourceOrderService;
    @Mock
    private ReservationRequestService requestService;
    @Mock
    private InstantAdmissionService admissionService;

    private InstantReservationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InstantReservationProcessor(
                resourceOrderService,
                requestService,
                admissionService,
                3,
                2
        );
    }

    @Test
    void acceptedShouldKeepOrderStatusOwnedByMqResult() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenReturn("order-1");

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("ACCEPTED", result.getResultStatus());
        assertEquals("order-1", result.getOrderNo());
        assertFalse(result.isQueryRequired());
        verify(requestService).markAccepted(1L, "owner", "order-1");
        verify(requestService, never()).markFailed(anyLong(), anyString(), anyString());
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
    }

    @Test
    void stockMismatchShouldInvalidateRedisBeforeMarkingFailed() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenThrow(new InstantStockMismatchException("MySQL库存不足"));

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("REJECTED", result.getResultStatus());
        assertEquals("MYSQL_STOCK_REJECTED", result.getReasonCode());
        InOrder inOrder = inOrder(admissionService, requestService);
        inOrder.verify(admissionService).release(any(), eq("digest"), eq(true));
        inOrder.verify(requestService).markFailed(1L, "owner", "MySQL库存不足");
    }

    @Test
    void definiteBusinessRollbackShouldReleaseExactlyOnceBeforeFailed() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenThrow(new BizException("额度不足"));

        processor.process(request, "owner");

        InOrder inOrder = inOrder(admissionService, requestService);
        inOrder.verify(admissionService).release(any(), eq("digest"), eq(false));
        inOrder.verify(requestService).markFailed(1L, "owner", "额度不足");
        verify(admissionService, times(1)).release(any(), anyString(), anyBoolean());
    }

    @Test
    void failedRedisCompensationMustNotMarkRequestFailed() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenThrow(new BizException("额度不足"));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(admissionService).release(any(), eq("digest"), eq(false));

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("PROCESSING", result.getResultStatus());
        verify(requestService).markRetry(eq(1L), eq("owner"), any(), eq("额度不足"));
        verify(requestService, never()).markFailed(anyLong(), anyString(), anyString());
    }

    @Test
    void technicalUnknownMustRetryWithoutRedisRelease() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenThrow(new IllegalStateException("commit result unknown"));
        when(requestService.findByRequestId("request-1")).thenReturn(request);

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("PROCESSING", result.getResultStatus());
        assertEquals("TECHNICAL_UNKNOWN", result.getReasonCode());
        verify(requestService).markRetry(eq(1L), eq("owner"), any(), eq("commit result unknown"));
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
        verify(requestService, never()).markFailed(anyLong(), anyString(), anyString());
    }

    @Test
    void commitUnknownButAcceptedEvidenceShouldReturnAccepted() {
        ReservationRequestEntity request = request(0);
        ReservationRequestEntity accepted = request(0);
        accepted.setStatus(ReservationRequestStatusEnum.ACCEPTED.getStatus());
        accepted.setOrderNo("order-committed");
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(true);
        when(resourceOrderService.createInstantAfterAdmission(any(), eq(1L), eq("owner")))
                .thenThrow(new IllegalStateException("commit response lost"));
        when(requestService.findByRequestId("request-1")).thenReturn(accepted);

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("ACCEPTED", result.getResultStatus());
        assertEquals("order-committed", result.getOrderNo());
        verify(requestService, never()).markRetry(anyLong(), anyString(), any(), anyString());
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
    }

    @Test
    void missingCredentialIsUnknownAndMustNotBeMarkedFailed() {
        ReservationRequestEntity request = request(0);
        when(admissionService.digest(any())).thenReturn("digest");
        when(admissionService.isHeld(any(), eq("digest"))).thenReturn(false);

        InstantReservationResultDto result = processor.process(request, "owner");

        assertEquals("PROCESSING", result.getResultStatus());
        verify(requestService).markRetry(eq(1L), eq("owner"), any(), contains("凭证"));
        verify(requestService, never()).markFailed(anyLong(), anyString(), anyString());
        verifyNoInteractions(resourceOrderService);
    }

    private ReservationRequestEntity request(int retryCount) {
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setId(1L);
        request.setRequestId("request-1");
        request.setUserId(1001L);
        request.setResourceId(10L);
        request.setStockItemId(20L);
        request.setQuantity(1);
        request.setStatus(ReservationRequestStatusEnum.PROCESSING.getStatus());
        request.setRetryCount(retryCount);
        return request;
    }
}
