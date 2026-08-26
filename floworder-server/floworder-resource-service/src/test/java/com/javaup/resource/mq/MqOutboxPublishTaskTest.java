package com.javaup.resource.mq;

import com.javaup.mq.OutboxPublishResult;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.metrics.OutboxPublishMetrics;
import com.javaup.resource.mq.publisher.OutboxMessagePublisher;
import com.javaup.resource.mq.service.MqOutboxService;
import com.javaup.resource.mq.task.MqOutboxPublishTask;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MqOutboxPublishTaskTest {

    private final MqOutboxService service = mock(MqOutboxService.class);
    private final OutboxMessagePublisher publisher = mock(OutboxMessagePublisher.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final OutboxPublishMetrics metrics = mock(OutboxPublishMetrics.class);

    @Test
    void shouldReservePermitBeforeLimitedQueryClaimAndSubmit() {
        MqOutboxEntity record = record();
        when(service.findSendable(3)).thenReturn(List.of(record), List.of());
        when(service.claim(eq(1L), anyString(), eq(60L))).thenReturn("token-1");
        when(publisher.publish(record, "token-1")).thenReturn(OutboxPublishResult.ack(5));
        when(service.markSent(1L, "token-1")).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        task(3, 250).publish();

        InOrder order = inOrder(service, executor);
        order.verify(service).findSendable(3);
        order.verify(service).claim(eq(1L), anyString(), eq(60L));
        order.verify(executor).execute(any(Runnable.class));
        order.verify(service).findSendable(3);
        verify(service).markSent(1L, "token-1");
    }

    @Test
    void executorRejectShouldReleaseWithDelayWithoutMarkFailed() {
        MqOutboxEntity record = record();
        when(service.findSendable(1)).thenReturn(List.of(record), List.of());
        when(service.claim(eq(1L), anyString(), eq(60L))).thenReturn("token-1");
        doThrow(new TaskRejectedException("full"))
                .when(executor).execute(any(Runnable.class));
        when(service.releaseClaim(1L, "token-1", 250L, "本机Outbox发布执行器已满"))
                .thenReturn(true);

        task(1, 250).publish();

        verify(service).releaseClaim(1L, "token-1", 250L, "本机Outbox发布执行器已满");
        verify(service, never()).markFailed(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void shouldContinueDispatchingWithinSameInvocationWhenWorkerReleasesPermit() {
        MqOutboxEntity first = record(1L, "message-1");
        MqOutboxEntity second = record(2L, "message-2");
        when(service.findSendable(1)).thenReturn(List.of(first), List.of(second), List.of());
        when(service.claim(eq(1L), anyString(), eq(60L))).thenReturn("token-1");
        when(service.claim(eq(2L), anyString(), eq(60L))).thenReturn("token-2");
        when(publisher.publish(first, "token-1")).thenReturn(OutboxPublishResult.ack(5));
        when(publisher.publish(second, "token-2")).thenReturn(OutboxPublishResult.ack(5));
        when(service.markSent(anyLong(), anyString())).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        task(1, 250).publish();

        verify(service, times(3)).findSendable(1);
        verify(publisher).publish(first, "token-1");
        verify(publisher).publish(second, "token-2");
        verify(metrics, times(2)).dispatched();
        verify(metrics).emptyScan();
    }

    @Test
    void blockedDispatcherShouldWakeAsSoonAsAsyncWorkerReleasesPermit() throws Exception {
        MqOutboxEntity first = record(1L, "message-1");
        MqOutboxEntity second = record(2L, "message-2");
        when(service.findSendable(1)).thenReturn(List.of(first), List.of(second), List.of());
        when(service.claim(eq(1L), anyString(), eq(60L))).thenReturn("token-1");
        when(service.claim(eq(2L), anyString(), eq(60L))).thenReturn("token-2");
        when(publisher.publish(first, "token-1")).thenReturn(OutboxPublishResult.ack(5));
        when(publisher.publish(second, "token-2")).thenReturn(OutboxPublishResult.ack(5));
        when(service.markSent(anyLong(), anyString())).thenReturn(true);
        BlockingQueue<Runnable> submitted = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));

        ExecutorService scannerThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> scan = scannerThread.submit(() -> task(1, 250).publish());
            Runnable firstWorker = submitted.poll(1, TimeUnit.SECONDS);
            assertNotNull(firstWorker);
            assertNull(submitted.poll(100, TimeUnit.MILLISECONDS));

            firstWorker.run();
            Runnable secondWorker = submitted.poll(1, TimeUnit.SECONDS);
            assertNotNull(secondWorker);
            secondWorker.run();

            scan.get(1, TimeUnit.SECONDS);
            verify(service, times(3)).findSendable(1);
        } finally {
            scannerThread.shutdownNow();
        }
    }

    private MqOutboxPublishTask task(int maxInFlight, long backpressureDelay) {
        return new MqOutboxPublishTask(
                service, publisher, executor, metrics,
                20, 60, backpressureDelay, 0, 200, maxInFlight, "resource-test"
        );
    }

    private MqOutboxEntity record() {
        return record(1L, "message-1");
    }

    private MqOutboxEntity record(long id, String messageId) {
        MqOutboxEntity record = new MqOutboxEntity();
        record.setId(id);
        record.setMessageId(messageId);
        record.setRetryCount(0);
        return record;
    }
}
