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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void newAdmissionThatFindsSameMysqlRequestMustKeepSharedCredential() {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity accepted = request(ReservationRequestStatusEnum.ACCEPTED, "order-1");
        when(admissionService.admit(dto)).thenReturn(attempt(InstantAdmissionResultEnum.ADMITTED_NEW));
        when(requestService.submitInstant(dto, "trace-1"))
                .thenReturn(new ReservationRequestService.InstantRequestSubmission(accepted, false));

        InstantReservationResultDto result = service.submit(dto, "trace-1");

        assertEquals("ACCEPTED", result.getResultStatus());
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
        verify(admissionService).markPersistedBestEffort("request-1");
        verify(requestService, never()).claim(anyLong(), anyString(), any(), any());
        verifyNoInteractions(processor);
    }

    @Test
    void concurrentNewAdmissionLosingMysqlInsertMustNotReleaseCredential() throws Exception {
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity pending = request(ReservationRequestStatusEnum.PENDING, null);
        ReservationRequestEntity accepted = request(ReservationRequestStatusEnum.ACCEPTED, "order-1");
        CountDownLatch newAdmissionReached = new CountDownLatch(1);
        CountDownLatch duplicateCompleted = new CountDownLatch(1);

        when(admissionService.admit(dto)).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("new-admission")) {
                newAdmissionReached.countDown();
                assertTrue(duplicateCompleted.await(5, TimeUnit.SECONDS));
                return attempt(InstantAdmissionResultEnum.ADMITTED_NEW);
            }
            assertTrue(newAdmissionReached.await(5, TimeUnit.SECONDS));
            return attempt(InstantAdmissionResultEnum.ADMITTED_DUPLICATE);
        });
        when(requestService.submitInstant(dto, "trace-1")).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("duplicate-admission")) {
                return new ReservationRequestService.InstantRequestSubmission(pending, true);
            }
            return new ReservationRequestService.InstantRequestSubmission(accepted, false);
        });
        when(requestService.claim(eq(1L), startsWith("instant-http-"), any(), any()))
                .thenAnswer(invocation -> Thread.currentThread().getName().equals("duplicate-admission"));
        when(processor.process(eq(pending), startsWith("instant-http-"))).thenAnswer(invocation -> {
            duplicateCompleted.countDown();
            return InstantReservationProcessor.accepted("request-1", "order-1");
        });

        ExecutorService newAdmissionExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "new-admission"));
        ExecutorService duplicateAdmissionExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "duplicate-admission"));
        try {
            Future<InstantReservationResultDto> first = newAdmissionExecutor.submit(
                    () -> service.submit(dto, "trace-1"));
            assertTrue(newAdmissionReached.await(5, TimeUnit.SECONDS));
            Future<InstantReservationResultDto> second = duplicateAdmissionExecutor.submit(
                    () -> service.submit(dto, "trace-1"));

            assertEquals("ACCEPTED", first.get(5, TimeUnit.SECONDS).getResultStatus());
            assertEquals("ACCEPTED", second.get(5, TimeUnit.SECONDS).getResultStatus());
        } finally {
            newAdmissionExecutor.shutdownNow();
            duplicateAdmissionExecutor.shutdownNow();
        }

        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
        verify(requestService, times(2)).submitInstant(dto, "trace-1");
        verify(processor, times(1)).process(eq(pending), startsWith("instant-http-"));
    }

    @Test
    void sameRequestTwoThreadsMustCreateAndProcessOnlyOnce() throws Exception {
        assertConcurrentSameRequest(2);
    }

    @Test
    void sameRequestFiveThreadsMustCreateAndProcessOnlyOnce() throws Exception {
        assertConcurrentSameRequest(5);
    }

    @Test
    void sameRequestHighConcurrencyMustCreateAndProcessOnlyOnce() throws Exception {
        assertConcurrentSameRequest(64);
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

    private void assertConcurrentSameRequest(int threads) throws Exception {
        reset(admissionService, requestService, processor);
        ResourceOrderCreateDto dto = dto();
        ReservationRequestEntity pending = request(ReservationRequestStatusEnum.PENDING, null);
        ReservationRequestEntity accepted = request(ReservationRequestStatusEnum.ACCEPTED, "order-1");
        AtomicInteger admissionSequence = new AtomicInteger();
        AtomicBoolean inserted = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicInteger processorCalls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        when(admissionService.admit(dto)).thenAnswer(invocation -> attempt(
                admissionSequence.getAndIncrement() == 0
                        ? InstantAdmissionResultEnum.ADMITTED_NEW
                        : InstantAdmissionResultEnum.ADMITTED_DUPLICATE
        ));
        when(requestService.submitInstant(dto, "trace-1")).thenAnswer(invocation ->
                new ReservationRequestService.InstantRequestSubmission(
                        pending,
                        inserted.compareAndSet(false, true)
                )
        );
        when(requestService.claim(eq(1L), startsWith("instant-http-"), any(), any()))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true));
        when(requestService.findByRequestId("request-1")).thenReturn(accepted);
        when(processor.process(eq(pending), startsWith("instant-http-"))).thenAnswer(invocation -> {
            processorCalls.incrementAndGet();
            return InstantReservationProcessor.accepted("request-1", "order-1");
        });

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<InstantReservationResultDto>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return service.submit(dto, "trace-1");
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<InstantReservationResultDto> future : futures) {
                assertNotNull(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(inserted.get(), "reservation_request事实应只创建一次");
        assertEquals(1, processorCalls.get(), "claim CAS后只允许一个processor执行");
        verify(admissionService, never()).release(any(), anyString(), anyBoolean());
        verify(processor, times(1)).process(eq(pending), startsWith("instant-http-"));
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
