package com.javaup.resource;

import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.service.ReservationRequestProcessor;
import com.javaup.resource.service.ReservationRequestService;
import com.javaup.resource.task.ReservationRequestDispatchTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationRequestDispatchTaskTest {

    @Mock
    private ReservationRequestService requestService;

    @Mock
    private ReservationRequestProcessor processor;

    private ThreadPoolExecutor executor;
    private ReservationRequestDispatchTask dispatchTask;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );
        dispatchTask = new ReservationRequestDispatchTask(
                requestService,
                processor,
                executor,
                10,
                30,
                3
        );
    }

    @AfterEach
    void shutdown() {
        dispatchTask.shutdown();
    }

    @Test
    void claimFailureShouldNotSubmitWorkerTask() {
        ReservationRequestEntity request = request();
        when(requestService.findClaimable(any(), eq(10)))
                .thenReturn(List.of(request));
        when(requestService.claim(anyLong(), anyString(), any(), any()))
                .thenReturn(false);

        dispatchTask.dispatch();

        verify(requestService).recoverExpired(any(), eq(10), eq(3));
        verifyNoInteractions(processor);
        verify(requestService, never())
                .releaseClaim(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void rejectedTaskShouldReleaseClaimWithoutConsumingRetry() {
        ReservationRequestEntity request = request();
        when(requestService.findClaimable(any(), eq(10)))
                .thenReturn(List.of(request));
        when(requestService.claim(anyLong(), anyString(), any(), any()))
                .thenReturn(true);

        executor.shutdown();
        dispatchTask.dispatch();

        verify(requestService).releaseClaim(
                eq(1L),
                anyString(),
                any(LocalDateTime.class),
                eq("v8 worker queue is full")
        );
        verifyNoInteractions(processor);
    }

    private ReservationRequestEntity request() {
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setId(1L);
        request.setRequestId("request-1");
        return request;
    }
}
