package com.javaup.mq;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.javaup.constant.OrderMqConstant.ORDER_SERVICE;
import static com.javaup.constant.OrderMqConstant.RESOURCE_SERVICE;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.order.timeout-scan-enabled=false",
        "floworder.admin.enabled=false"
})
class MqOutboxServiceIntegrationTest {

    @Resource
    private MqOutboxService outboxService;

    @Resource
    private MqOutboxMapper outboxMapper;

    private final List<Long> ids = new ArrayList<>();

    @AfterEach
    void cleanData() {
        ids.forEach(outboxMapper::deleteById);
    }

    @Test
    void concurrentClaimShouldHaveOnlyOneWinner() throws Exception {
        MqOutboxEntity outbox = insertOutbox(ORDER_SERVICE, 0, LocalDateTime.now().minusSeconds(1), null, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            ready.countDown();
            start.await();
            return outboxService.claim(outbox.getId());
        };

        try {
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();

            int winners = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(10, outboxMapper.selectById(outbox.getId()).getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void futureRetryShouldNotBeClaimed() {
        MqOutboxEntity outbox = insertOutbox(ORDER_SERVICE, 30, LocalDateTime.now().plusMinutes(1), null, 1);

        assertFalse(outboxService.claim(outbox.getId()));
        assertFalse(outboxService.findSendable(100).stream()
                .anyMatch(record -> record.getId().equals(outbox.getId())));
    }

    @Test
    void failedMessageShouldRetryAndEventuallyBecomeDead() {
        MqOutboxEntity outbox = insertOutbox(ORDER_SERVICE, 10, LocalDateTime.now(), null, 0);

        outboxService.markFailed(outbox.getId(), 0, "first failure");
        MqOutboxEntity retry = outboxMapper.selectById(outbox.getId());
        assertEquals(30, retry.getStatus());
        assertEquals(1, retry.getRetryCount());
        assertNotNull(retry.getNextRetryTime());

        retry.setStatus(10);
        retry.setRetryCount(4);
        retry.setClaimUntil(LocalDateTime.now().plusSeconds(60));
        assertEquals(1, outboxMapper.updateById(retry));

        outboxService.markFailed(outbox.getId(), 4, "final failure");
        MqOutboxEntity dead = outboxMapper.selectById(outbox.getId());
        assertEquals(40, dead.getStatus());
        assertEquals(5, dead.getRetryCount());
        assertNull(dead.getNextRetryTime());
        assertNull(dead.getClaimUntil());
    }

    @Test
    void reclaimShouldOnlyRecoverExpiredClaimsOwnedByOrderService() {
        MqOutboxEntity expired = insertOutbox(
                ORDER_SERVICE, 10, LocalDateTime.now(), LocalDateTime.now().minusSeconds(1), 0);
        MqOutboxEntity active = insertOutbox(
                ORDER_SERVICE, 10, LocalDateTime.now(), LocalDateTime.now().plusMinutes(1), 0);
        MqOutboxEntity otherService = insertOutbox(
                RESOURCE_SERVICE, 10, LocalDateTime.now(), LocalDateTime.now().minusSeconds(1), 0);

        outboxService.reclaimExpiredClaims();

        assertEquals(30, outboxMapper.selectById(expired.getId()).getStatus());
        assertEquals(10, outboxMapper.selectById(active.getId()).getStatus());
        assertEquals(10, outboxMapper.selectById(otherService.getId()).getStatus());
    }

    private MqOutboxEntity insertOutbox(
            String producer,
            int status,
            LocalDateTime nextRetryTime,
            LocalDateTime claimUntil,
            int retryCount) {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(suffix);
        outbox.setProducerService(producer);
        outbox.setBizKey("V4-OUTBOX-" + suffix);
        outbox.setMessageType("V4_TEST");
        outbox.setExchangeName("v4.test.exchange");
        outbox.setRoutingKey("v4.test.key");
        outbox.setContent("{}");
        outbox.setStatus(status);
        outbox.setRetryCount(retryCount);
        outbox.setNextRetryTime(nextRetryTime);
        outbox.setClaimUntil(claimUntil);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        assertEquals(1, outboxMapper.insert(outbox));
        ids.add(outbox.getId());
        return outbox;
    }
}
