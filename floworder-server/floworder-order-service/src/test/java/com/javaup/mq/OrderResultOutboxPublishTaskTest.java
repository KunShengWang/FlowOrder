package com.javaup.mq;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.metrics.OutboxPublishMetrics;
import com.javaup.mq.publisher.OrderResultOutboxPublisher;
import com.javaup.mq.service.MqOutboxService;
import com.javaup.mq.task.OrderResultOutboxPublishTask;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static com.javaup.mq.OutboxPublishResult.Outcome.NACK;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderResultOutboxPublishTaskTest {

    @Test
    void failedPublishShouldUseClaimTokenWhenMarkingRetry() {
        MqOutboxService service = mock(MqOutboxService.class);
        OrderResultOutboxPublisher publisher = mock(OrderResultOutboxPublisher.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        OutboxPublishMetrics metrics = mock(OutboxPublishMetrics.class);
        MqOutboxEntity record = new MqOutboxEntity();
        record.setId(2L);
        record.setMessageId("message-2");
        record.setRetryCount(1);

        when(service.findSendable(2)).thenReturn(List.of(record));
        when(service.claim(eq(2L), anyString(), eq(60L))).thenReturn("token-2");
        when(publisher.publish(record, "token-2"))
                .thenReturn(OutboxPublishResult.failed(NACK, "nack", 8));
        when(service.markFailed(2L, "token-2", 1, "nack")).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        new OrderResultOutboxPublishTask(
                service, publisher, executor, metrics,
                20, 60, 250, 0, 2, "order-test"
        ).publish();

        verify(service).findSendable(2);
        verify(service).markFailed(2L, "token-2", 1, "nack");
        verify(service, never()).markSent(anyLong(), anyString());
    }
}
