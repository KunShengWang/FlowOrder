package com.javaup.resource;

import com.javaup.dto.InstantReservationResultDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import com.javaup.resource.enums.ReservationProcessingModeEnum;
import com.javaup.resource.enums.ReservationRequestStatusEnum;
import com.javaup.resource.service.InstantAdmissionService;
import com.javaup.resource.service.InstantReservationProcessor;
import com.javaup.resource.service.ReservationRequestService;
import com.javaup.resource.service.impl.InstantReservationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstantReservationServiceImplTest {

    @Mock
    private InstantAdmissionService admissionService;
    @Mock
    private ReservationRequestService requestService;
    @Mock
    private InstantReservationProcessor processor;

    private InstantReservationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InstantReservationServiceImpl(
                admissionService,
                requestService,
                processor,
                30
        );
    }

    @Test
    void redisUnavailableMustFailBeforeMysqlPersistence() {
        ResourceOrderCreateDto dto = dto();
        when(admissionService.admit(dto)).thenThrow(new BizException("Redis准入不可用，请稍后重试"));

        assertThrows(BizException.class, () -> service.submit(dto, "trace-1"));

        verifyNoInteractions(requestService, processor);
    }

    @Test
    void soldOutShouldReturnRejectedWithoutMysqlPersistence() {
        ResourceOrderCreateDto dto = dto();
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.SOLD_OUT));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("REJECTED", result.getResultStatus());
        assertEquals("SOLD_OUT", result.getReasonCode());
        verifyNoInteractions(requestService, processor);
    }

    @Test
    void digestConflictShouldFailWithoutMysqlPersistence() {
        ResourceOrderCreateDto dto = dto();
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.IDEMPOTENT_CONFLICT));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("REJECTED", result.getResultStatus());
        assertEquals("IDEMPOTENT_CONFLICT", result.getReasonCode());
        verifyNoInteractions(requestService, processor);
    }

    @Test
    void newAdmissionShouldPersistClaimAndProcessSynchronously() {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity pending = request(ReservationRequestStatusEnum.PENDING, null);
        InstantReservationResultDto accepted = InstantReservationProcessor.accepted("request-1", "order-1");
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenReturn(new ReservationRequestService.InstantRequestSubmission(pending, true));
        when(requestService.claim(eq(1L), startsWith("instant-http-"), any(), any())).thenReturn(true);
        when(processor.process(eq(pending), startsWith("instant-http-"))).thenReturn(accepted);

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertSame(accepted, result);
        verify(admissionService).markPersistedBestEffort("request-1");
        verify(requestService).claim(eq(1L), startsWith("instant-http-"), any(), any());
    }

    @Test
    void repeatedAcceptedRequestShouldReturnOriginalOrderWithoutClaim() {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity accepted = request(ReservationRequestStatusEnum.ACCEPTED, "order-1");
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_DUPLICATE));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenReturn(new ReservationRequestService.InstantRequestSubmission(accepted, false));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("ACCEPTED", result.getResultStatus());
        assertEquals("order-1", result.getOrderNo());
        verify(requestService, never()).claim(anyLong(), anyString(), any(), any());
        verifyNoInteractions(processor);
    }

    @Test
    void unknownPersistenceShouldReturnProcessingAndLeaveTokenForOrphanRecovery() {
        ResourceOrderCreateDto dto = dto();
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenThrow(new IllegalStateException("insert result unknown"));
        when(requestService.findByRequestId("request-1")).thenReturn(null);

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("PROCESSING", result.getResultStatus());
        assertEquals("PERSISTENCE_UNKNOWN", result.getReasonCode());
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
        verify(admissionService, never()).markPersistedBestEffort(anyString());
    }

    @Test
    void insertExceptionWithVisibleRowMustNotReleaseBecauseCommitMayHaveSucceeded() {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity pending = request(ReservationRequestStatusEnum.PENDING, null);
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenThrow(new IllegalStateException("insert response unknown"));
        when(requestService.findByRequestId("request-1")).thenReturn(pending);
        when(requestService.claim(eq(1L), startsWith("instant-http-"), any(), any())).thenReturn(false);
        when(requestService.findByRequestId("request-1")).thenReturn(pending);

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("PROCESSING", result.getResultStatus());
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
    }

    @Test
    void newRedisAdmissionForExistingMysqlRequestMustBeReleased() {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity accepted = request(ReservationRequestStatusEnum.ACCEPTED, "order-1");
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenReturn(new ReservationRequestService.InstantRequestSubmission(accepted, false));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("ACCEPTED", result.getResultStatus());
        verify(admissionService).release(dto, "digest", false);
        verify(requestService, never()).claim(anyLong(), anyString(), any(), any());
        verifyNoInteractions(processor);
    }

    @Test
    void mysqlDigestConflictAfterNewAdmissionMustReleaseExtraToken() {
        ResourceOrderCreateDto dto = dto();
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenThrow(new BizException("相同requestId对应的预约参数不一致"));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("REJECTED", result.getResultStatus());
        assertEquals("IDEMPOTENT_CONFLICT", result.getReasonCode());
        verify(admissionService).release(dto, "digest", false);
        verifyNoInteractions(processor);
    }

    private InstantAdmissionService.AdmissionAttempt attempt(InstantAdmissionResultEnum result) {
        return new InstantAdmissionService.AdmissionAttempt(result, "digest");
    }

    private ResourceOrderCreateDto dto() {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setRequestId("request-1");
        dto.setUserId(1001L);
        dto.setResourceId(10L);
        dto.setStockItemId(20L);
        dto.setQuantity(1);
        return dto;
    }

    private ReservationRequestEntity request(ReservationRequestStatusEnum status, String orderNo) {
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setId(1L);
        request.setRequestId("request-1");
        request.setUserId(1001L);
        request.setResourceId(10L);
        request.setStockItemId(20L);
        request.setQuantity(1);
        request.setProcessingMode(ReservationProcessingModeEnum.INSTANT.getMode());
        request.setStatus(status.getStatus());
        request.setOrderNo(orderNo);
        request.setRetryCount(0);
        return request;
    }
}
