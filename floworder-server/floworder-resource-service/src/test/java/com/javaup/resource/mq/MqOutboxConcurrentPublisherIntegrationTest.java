package com.javaup.resource.mq;

import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.javaup.constant.OrderMqConstant.RESOURCE_SERVICE;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.v8.enabled=false",
        "floworder.instant.enabled=false",
        "floworder.admin.enabled=false"
})
class MqOutboxConcurrentPublisherIntegrationTest {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

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
        MqOutboxEntity outbox = insertOutbox(0, LocalDateTime.now().minusSeconds(1), null, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            ready.countDown();
            start.await();
            return outboxService.claim(outbox.getId(), "resource-test", 60);
        };

        try {
            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            int winners = (first.get(5, TimeUnit.SECONDS) != null ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) != null ? 1 : 0);
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleWorkerShouldBeFencedAfterLeaseRecovery() {
        MqOutboxEntity outbox = insertOutbox(
                10, LocalDateTime.now(), LocalDateTime.now().minusSeconds(1), 0);
        String oldToken = outbox.getClaimToken();

        assertEquals(1, outboxService.reclaimExpiredClaims(10));
        String newToken = outboxService.claim(outbox.getId(), "resource-new", 60);
        assertNotNull(newToken);

        assertFalse(outboxService.markSent(outbox.getId(), oldToken));
        assertFalse(outboxService.markFailed(outbox.getId(), oldToken, 0, "stale"));
        assertFalse(outboxService.releaseClaim(outbox.getId(), oldToken, 200, "stale"));
        assertTrue(outboxService.markSent(outbox.getId(), newToken));
    }

    @Test
    void localBackpressureShouldNotConsumeRabbitRetry() {
        MqOutboxEntity outbox = insertOutbox(
                10, LocalDateTime.now(), LocalDateTime.now().plusSeconds(60), 3);

        assertTrue(outboxService.releaseClaim(
                outbox.getId(), outbox.getClaimToken(), 300, "local backpressure"));
        MqOutboxEntity released = outboxMapper.selectById(outbox.getId());
        assertEquals(30, released.getStatus());
        assertEquals(3, released.getRetryCount());
        assertTrue(released.getNextRetryTime().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    private MqOutboxEntity insertOutbox(
            int status,
            LocalDateTime nextRetryTime,
            LocalDateTime claimUntil,
            int retryCount
    ) {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(suffix);
        outbox.setProducerService(RESOURCE_SERVICE);
        outbox.setBizKey("RESOURCE-OUTBOX-" + suffix);
        outbox.setMessageType("RESOURCE_TEST");
        outbox.setExchangeName("test.exchange");
        outbox.setRoutingKey("test.key");
        outbox.setContent("{}");
        outbox.setStatus(status);
        outbox.setRetryCount(retryCount);
        outbox.setNextRetryTime(nextRetryTime);
        outbox.setClaimUntil(claimUntil);
        if (status == 10) {
            outbox.setClaimOwner("resource-old");
            outbox.setClaimToken("token-" + suffix);
        }
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        assertEquals(1, outboxMapper.insert(outbox));
        ids.add(outbox.getId());
        return outbox;
    }
}
