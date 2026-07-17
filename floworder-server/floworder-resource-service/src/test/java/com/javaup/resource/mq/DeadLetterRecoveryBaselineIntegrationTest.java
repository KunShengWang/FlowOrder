package com.javaup.resource.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.client.OrderMqAdminClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqConsumeLogEntity;
import com.javaup.resource.entity.MqDeadLetterEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import com.javaup.resource.mapper.MqConsumeLogMapper;
import com.javaup.resource.mapper.MqDeadLetterMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.mq.service.OrderStateMessageService;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.enums.OrderStatusEnum.RESERVED;
import static com.javaup.enums.OrderStatusEnum.TIMEOUT;
import static com.javaup.resource.enums.StockDeductStatusEnum.ORDER_CREATED;
import static com.javaup.resource.enums.StockDeductStatusEnum.RELEASED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderCare M0.5 恢复域基线。
 *
 * <p>这里直接使用真实 Spring 事务和 MySQL Mapper，覆盖死信抢占、库存释放消费幂等、
 * 业务收敛关闭以及 stale REPLAYING 恢复。订单服务的“重新投递”HTTP 边界使用 mock，
 * RabbitMQ 跨服务传输留给后续 E2E 验证。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.mq.dead-letter-monitor.enabled=false",
        "floworder.compensation.enabled=false",
        "floworder.v8.enabled=false",
        "floworder.admin.enabled=false"
})
class DeadLetterRecoveryBaselineIntegrationTest {

    private static final int DEAD_PENDING = 0;
    private static final int DEAD_REPLAYING = 10;
    private static final int DEAD_RESOLVED = 20;

    @Resource
    private MqDeadLetterService deadLetterService;

    @Resource
    private OrderStateMessageService messageService;

    @Resource
    private MqDeadLetterMapper deadLetterMapper;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private MqConsumeLogMapper consumeLogMapper;

    @Resource
    private UserReservationQuotaMapper quotaMapper;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Resource
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderMqAdminClient orderMqAdminClient;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> deadLetterIds = new ArrayList<>();
    private final List<Long> reservationRequestIds = new ArrayList<>();
    private final List<Long> stockItemIds = new ArrayList<>();
    private final List<Long> quotaIds = new ArrayList<>();
    private final List<String> deductNos = new ArrayList<>();

    @AfterEach
    void cleanData() {
        deadLetterIds.forEach(deadLetterMapper::deleteById);
        reservationRequestIds.forEach(reservationRequestMapper::deleteById);
        for (String deductNo : deductNos) {
            consumeLogMapper.delete(
                    Wrappers.<MqConsumeLogEntity>lambdaQuery()
                            .eq(MqConsumeLogEntity::getBizKey, deductNo)
            );
            deductRecordMapper.delete(
                    Wrappers.<StockDeductRecordEntity>lambdaQuery()
                            .eq(StockDeductRecordEntity::getDeductNo, deductNo)
            );
        }
        quotaIds.forEach(quotaMapper::deleteById);
        stockItemIds.forEach(stockItemMapper::deleteById);
    }

    @Test
    void replayThenConsumeShouldResolveCaseAndDuplicateDeliveryShouldNotReleaseTwice()
            throws Exception {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = timeoutMessage(fixture);
        MqDeadLetterEntity deadLetter =
                insertStateDeadLetter(message, DEAD_PENDING, null);
        when(orderMqAdminClient.replayConsumerDead(message.getMessageId()))
                .thenReturn(ApiResponse.success());

        deadLetterService.replay(deadLetter.getId(), "ordercare-m0.5");

        MqDeadLetterEntity replaying = deadLetterMapper.selectById(deadLetter.getId());
        assertEquals(DEAD_REPLAYING, replaying.getStatus());
        assertEquals(1, replaying.getReplayCount());

        assertEquals(fixture.stockItemId(), messageService.handle(message));
        deadLetterService.resolveOrderState(message);

        // 模拟 RabbitMQ 重复投递同一 messageId；业务消费者和死信关闭都必须幂等。
        assertEquals(fixture.stockItemId(), messageService.handle(message));
        deadLetterService.resolveOrderState(message);

        assertEquals(DEAD_RESOLVED,
                deadLetterMapper.selectById(deadLetter.getId()).getStatus());
        assertRecovered(fixture);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
        verify(orderMqAdminClient, times(1))
                .replayConsumerDead(message.getMessageId());
    }

    @Test
    void concurrentReplayShouldAllowOnlyOneClaimAndOneRemoteSubmission()
            throws Exception {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = timeoutMessage(fixture);
        MqDeadLetterEntity deadLetter =
                insertStateDeadLetter(message, DEAD_PENDING, null);
        when(orderMqAdminClient.replayConsumerDead(message.getMessageId()))
                .thenReturn(ApiResponse.success());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> replayAfterBarrier(
                            deadLetter.getId(), ready, start)),
                    executor.submit(() -> replayAfterBarrier(
                            deadLetter.getId(), ready, start))
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }

            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }

        MqDeadLetterEntity actual = deadLetterMapper.selectById(deadLetter.getId());
        assertEquals(DEAD_REPLAYING, actual.getStatus());
        assertEquals(1, actual.getReplayCount());
        verify(orderMqAdminClient, times(1))
                .replayConsumerDead(message.getMessageId());
    }

    @Test
    void staleReplayingShouldResolveWhenTimeoutBusinessAlreadyConverged()
            throws Exception {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = timeoutMessage(fixture);
        assertEquals(fixture.stockItemId(), messageService.handle(message));
        MqDeadLetterEntity deadLetter = insertStateDeadLetter(
                message,
                DEAD_REPLAYING,
                LocalDateTime.now().minusMinutes(10)
        );

        deadLetterService.recoverStaleReplaying(
                LocalDateTime.now().minusMinutes(5),
                100
        );

        assertEquals(DEAD_RESOLVED,
                deadLetterMapper.selectById(deadLetter.getId()).getStatus());
        assertRecovered(fixture);
    }

    @Test
    void staleReplayingShouldReturnToPendingWhenTimeoutBusinessDidNotConverge()
            throws Exception {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = timeoutMessage(fixture);
        MqDeadLetterEntity deadLetter = insertStateDeadLetter(
                message,
                DEAD_REPLAYING,
                LocalDateTime.now().minusMinutes(10)
        );

        deadLetterService.recoverStaleReplaying(
                LocalDateTime.now().minusMinutes(5),
                100
        );

        MqDeadLetterEntity actual = deadLetterMapper.selectById(deadLetter.getId());
        assertEquals(DEAD_PENDING, actual.getStatus());
        assertEquals("重放结果确认超时", actual.getLastError());
        assertNotRecovered(fixture);
    }

    private boolean replayAfterBarrier(
            Long deadLetterId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            deadLetterService.replay(deadLetterId, "ordercare-concurrent-test");
            return true;
        } catch (BizException expectedLostClaim) {
            return false;
        }
    }

    private Fixture insertFixture() {
        String suffix = UUID.randomUUID().toString();
        long stockItemId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        LocalDateTime now = LocalDateTime.now();

        StockItemEntity stock = new StockItemEntity();
        stock.setId(stockItemId);
        stock.setStockItemCode("M05-STOCK-" + suffix);
        stock.setResourceId(1L);
        stock.setName("OrderCare M0.5 stock");
        stock.setTotalStock(10);
        stock.setAvailableStock(7);
        stock.setLockedStock(3);
        stock.setSoldStock(0);
        stock.setStatus(1);
        stock.setVersion(0);
        stock.setCreatedAt(now);
        stock.setUpdatedAt(now);
        stock.setDeleted(0);
        assertEquals(1, stockItemMapper.insert(stock));
        stockItemIds.add(stockItemId);

        String deductNo = "M05-DEDUCT-" + suffix;
        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setDeductNo(deductNo);
        record.setOrderNo("M05-ORDER-" + suffix);
        record.setUserId(1L);
        record.setResourceId(1L);
        record.setStockItemId(stockItemId);
        record.setQuantity(3);
        record.setRequestId("M05-REQUEST-" + suffix);
        record.setStatus(ORDER_CREATED.getCode());
        record.setExpireTime(now.plusMinutes(10));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setRetryCount(0);
        record.setQueryErrorCount(0);
        record.setCreateMode(3);
        assertEquals(1, deductRecordMapper.insert(record));
        deductNos.add(deductNo);

        UserReservationQuotaEntity quota = new UserReservationQuotaEntity();
        quota.setResourceId(1L);
        quota.setStockItemId(stockItemId);
        quota.setUserId(record.getUserId());
        quota.setStatus(1);
        quota.setLimitQuantity(5);
        quota.setUsedQuantity(record.getQuantity());
        quota.setValidFrom(now.minusHours(1));
        quota.setValidUntil(now.plusHours(1));
        quota.setVersion(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        assertEquals(1, quotaMapper.insert(quota));
        quotaIds.add(quota.getId());

        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setRequestId(record.getRequestId());
        request.setTraceId("M05-TRACE-" + suffix);
        request.setUserId(record.getUserId());
        request.setResourceId(record.getResourceId());
        request.setStockItemId(stockItemId);
        request.setQuantity(record.getQuantity());
        request.setOrderNo(record.getOrderNo());
        request.setStatus(20);
        request.setOrderStatus(RESERVED.getCode());
        request.setOrderEventVersion(0);
        request.setRetryCount(0);
        request.setVersion(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        assertEquals(1, reservationRequestMapper.insert(request));
        reservationRequestIds.add(request.getId());

        return new Fixture(
                request.getId(),
                stockItemId,
                deductNo,
                record.getOrderNo(),
                record.getRequestId(),
                record.getUserId(),
                record.getQuantity()
        );
    }

    private OrderStateChangedMessage timeoutMessage(Fixture fixture) {
        OrderStateChangedMessage message = new OrderStateChangedMessage();
        message.setMessageId("M05-MESSAGE-" + UUID.randomUUID());
        message.setEventType(ORDER_TIMEOUT);
        message.setRequestId(fixture.requestId());
        message.setOrderNo(fixture.orderNo());
        message.setDeductNo(fixture.deductNo());
        message.setStockItemId(fixture.stockItemId());
        message.setQuantity(fixture.quantity());
        message.setFromStatus(RESERVED.getCode());
        message.setToStatus(TIMEOUT.getCode());
        message.setOccurredAt(LocalDateTime.now());
        return message;
    }

    private MqDeadLetterEntity insertStateDeadLetter(
            OrderStateChangedMessage message,
            int status,
            LocalDateTime replayedAt
    ) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        MqDeadLetterEntity deadLetter = new MqDeadLetterEntity();
        deadLetter.setMessageId(message.getMessageId());
        deadLetter.setDeadQueue(ORDER_STATE_DLQ);
        deadLetter.setProducerService(ORDER_SERVICE);
        deadLetter.setMessageType(message.getEventType());
        deadLetter.setBizKey(message.getDeductNo());
        deadLetter.setExchangeName(ORDER_STATE_EXCHANGE);
        deadLetter.setRoutingKey(ORDER_STATE_ROUTING_KEY);
        deadLetter.setContent(objectMapper.writeValueAsString(message));
        deadLetter.setDeathReason("OrderCare M0.5 injected failure");
        deadLetter.setStatus(status);
        deadLetter.setReplayCount(status == DEAD_REPLAYING ? 1 : 0);
        deadLetter.setReplayedAt(replayedAt);
        deadLetter.setCreatedAt(now);
        deadLetter.setUpdatedAt(now);
        assertEquals(1, deadLetterMapper.insert(deadLetter));
        deadLetterIds.add(deadLetter.getId());
        return deadLetter;
    }

    private void assertRecovered(Fixture fixture) {
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, fixture.deductNo())
        );
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId,
                                fixture.stockItemId())
                        .eq(UserReservationQuotaEntity::getUserId, fixture.userId())
        );
        ReservationRequestEntity request =
                reservationRequestMapper.selectById(fixture.reservationRequestId());

        assertEquals(RELEASED.getCode(), record.getStatus());
        assertEquals(10, stock.getAvailableStock());
        assertEquals(0, stock.getLockedStock());
        assertEquals(0, stock.getSoldStock());
        assertEquals(stock.getTotalStock(),
                stock.getAvailableStock()
                        + stock.getLockedStock()
                        + stock.getSoldStock());
        assertEquals(0, quota.getUsedQuantity());
        assertEquals(TIMEOUT.getCode(), request.getOrderStatus());
        assertEquals(ORDER_TIMEOUT, request.getLatestOrderEventType());
    }

    private void assertNotRecovered(Fixture fixture) {
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, fixture.deductNo())
        );

        assertEquals(ORDER_CREATED.getCode(), record.getStatus());
        assertEquals(7, stock.getAvailableStock());
        assertEquals(3, stock.getLockedStock());
        assertEquals(0, stock.getSoldStock());
    }

    private Long consumeLogCount(String deductNo) {
        return consumeLogMapper.selectCount(
                Wrappers.<MqConsumeLogEntity>lambdaQuery()
                        .eq(MqConsumeLogEntity::getBizKey, deductNo)
        );
    }

    private record Fixture(
            Long reservationRequestId,
            Long stockItemId,
            String deductNo,
            String orderNo,
            String requestId,
            Long userId,
            Integer quantity
    ) {
    }
}
