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
        when(service.findSendable(3)).thenReturn(List.of(record));
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
        verify(service).markSent(1L, "token-1");
    }

    @Test
    void executorRejectShouldReleaseWithDelayWithoutMarkFailed() {
        MqOutboxEntity record = record();
        when(service.findSendable(1)).thenReturn(List.of(record));
        when(service.claim(eq(1L), anyString(), eq(60L))).thenReturn("token-1");
        doThrow(new TaskRejectedException("full"))
                .when(executor).execute(any(Runnable.class));
        when(service.releaseClaim(1L, "token-1", 250L, "本机Outbox发布执行器已满"))
                .thenReturn(true);

        task(1, 250).publish();

        verify(service).releaseClaim(1L, "token-1", 250L, "本机Outbox发布执行器已满");
        verify(service, never()).markFailed(anyLong(), anyString(), anyInt(), anyString());
    }

    private MqOutboxPublishTask task(int maxInFlight, long backpressureDelay) {
        return new MqOutboxPublishTask(
                service, publisher, executor, metrics,
                20, 60, backpressureDelay, 0, maxInFlight, "resource-test"
        );
    }

    private MqOutboxEntity record() {
        MqOutboxEntity record = new MqOutboxEntity();
        record.setId(1L);
        record.setMessageId("message-1");
        record.setRetryCount(0);
        return record;
    }
}
