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
        Callable<String> task = () -> {
            ready.countDown();
            start.await();
            return outboxService.claim(outbox.getId(), "test-instance", 60);
        };

        try {
            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();

            int winners = (first.get(5, TimeUnit.SECONDS) != null ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) != null ? 1 : 0);
            assertEquals(1, winners);
            MqOutboxEntity claimed = outboxMapper.selectById(outbox.getId());
            assertEquals(10, claimed.getStatus());
            assertNotNull(claimed.getClaimToken());
            assertEquals("test-instance", claimed.getClaimOwner());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void futureRetryShouldNotBeClaimed() {
        MqOutboxEntity outbox = insertOutbox(ORDER_SERVICE, 30, LocalDateTime.now().plusMinutes(1), null, 1);

        assertNull(outboxService.claim(outbox.getId(), "test-instance", 60));
        assertFalse(outboxService.findSendable(100).stream()
                .anyMatch(record -> record.getId().equals(outbox.getId())));
    }

    @Test
    void failedMessageShouldRetryAndEventuallyBecomeDead() {
        MqOutboxEntity outbox = insertOutbox(ORDER_SERVICE, 10, LocalDateTime.now(), null, 0);

        assertTrue(outboxService.markFailed(
                outbox.getId(), outbox.getClaimToken(), 0, "first failure"));
        MqOutboxEntity retry = outboxMapper.selectById(outbox.getId());
        assertEquals(30, retry.getStatus());
        assertEquals(1, retry.getRetryCount());
        assertNotNull(retry.getNextRetryTime());

        retry.setStatus(10);
        retry.setRetryCount(4);
        retry.setClaimOwner("test-instance");
        retry.setClaimToken("final-token");
        retry.setClaimUntil(LocalDateTime.now().plusSeconds(60));
        assertEquals(1, outboxMapper.updateById(retry));

        assertTrue(outboxService.markFailed(outbox.getId(), "final-token", 4, "final failure"));
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

        assertEquals(1, outboxService.reclaimExpiredClaims(100));

        assertEquals(30, outboxMapper.selectById(expired.getId()).getStatus());
        assertEquals(10, outboxMapper.selectById(active.getId()).getStatus());
        assertEquals(10, outboxMapper.selectById(otherService.getId()).getStatus());
    }

    @Test
    void staleWorkerShouldNotOverwriteNewClaim() {
        MqOutboxEntity outbox = insertOutbox(
                ORDER_SERVICE, 10, LocalDateTime.now(), LocalDateTime.now().minusSeconds(1), 0);
        String oldToken = outbox.getClaimToken();

        assertEquals(1, outboxService.reclaimExpiredClaims(10));
        String newToken = outboxService.claim(outbox.getId(), "new-instance", 60);
        assertNotNull(newToken);
        assertNotEquals(oldToken, newToken);

        assertFalse(outboxService.markSent(outbox.getId(), oldToken));
        assertFalse(outboxService.markFailed(outbox.getId(), oldToken, 0, "stale"));
        assertFalse(outboxService.releaseClaim(outbox.getId(), oldToken, 200, "stale"));
        assertTrue(outboxService.markSent(outbox.getId(), newToken));
        assertEquals(20, outboxMapper.selectById(outbox.getId()).getStatus());
    }

    @Test
    void localBackpressureShouldDelayWithoutIncreasingRetryCount() {
        MqOutboxEntity outbox = insertOutbox(
                ORDER_SERVICE, 10, LocalDateTime.now(), LocalDateTime.now().plusSeconds(60), 2);

        LocalDateTime before = LocalDateTime.now();
        assertTrue(outboxService.releaseClaim(
                outbox.getId(), outbox.getClaimToken(), 300, "local backpressure"));

        MqOutboxEntity released = outboxMapper.selectById(outbox.getId());
        assertEquals(30, released.getStatus());
        assertEquals(2, released.getRetryCount());
        assertTrue(released.getNextRetryTime().isAfter(before));
        assertNull(released.getClaimToken());
        assertNull(released.getClaimOwner());
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
        if (status == 10) {
            outbox.setClaimOwner("test-instance");
            outbox.setClaimToken("token-" + suffix);
        }
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        assertEquals(1, outboxMapper.insert(outbox));
        ids.add(outbox.getId());
        return outbox;
    }
}
