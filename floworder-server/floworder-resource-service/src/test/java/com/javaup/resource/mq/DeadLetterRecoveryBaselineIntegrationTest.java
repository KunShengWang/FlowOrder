package com.javaup.resource.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.client.OrderMqAdminClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderCreateResultMessage;
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
import com.javaup.resource.mq.consumer.OrderResultConsumer;
import com.javaup.resource.mq.consumer.OrderStateConsumer;
import com.javaup.resource.mq.service.MqDeadLetterService;
import com.javaup.resource.mq.service.OrderStateMessageService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
import static com.javaup.resource.enums.StockDeductStatusEnum.MANUAL_REVIEW;
import static com.javaup.resource.enums.StockDeductStatusEnum.ORDER_CREATED;
import static com.javaup.resource.enums.StockDeductStatusEnum.PRE_DEDUCTED;
import static com.javaup.resource.enums.StockDeductStatusEnum.RELEASED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
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
    private OrderStateConsumer stateConsumer;

    @Resource
    private OrderResultConsumer resultConsumer;

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

    @BeforeEach
    void stubInstantCredentialUpdate() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);
    }

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

        Channel firstDelivery = consumeState(message, 1L);

        // 模拟 RabbitMQ 重复投递同一 messageId；业务消费者和死信关闭都必须幂等。
        Channel duplicateDelivery = consumeState(message, 2L);

        assertEquals(DEAD_RESOLVED,
                deadLetterMapper.selectById(deadLetter.getId()).getStatus());
        assertRecovered(fixture);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
        verify(firstDelivery).basicAck(1L, false);
        verify(duplicateDelivery).basicAck(2L, false);
        verify(orderMqAdminClient, times(1))
                .replayConsumerDead(message.getMessageId());
    }

    @Test
    void failedResultReplayShouldReleaseInventoryAndRemainIdempotent()
            throws Exception {
        Fixture fixture = insertFixture(PRE_DEDUCTED.getCode());
        OrderCreateResultMessage message = failedResultMessage(fixture);
        MqDeadLetterEntity deadLetter = insertResultDeadLetter(
                message, DEAD_PENDING, null);
        when(orderMqAdminClient.replayConsumerDead(message.getMessageId()))
                .thenReturn(ApiResponse.success());

        deadLetterService.replay(deadLetter.getId(), "ordercare-result-baseline");

        assertEquals(DEAD_REPLAYING,
                deadLetterMapper.selectById(deadLetter.getId()).getStatus());
        Channel firstDelivery = consumeResult(message, 11L);
        Channel duplicateDelivery = consumeResult(message, 12L);

        assertEquals(DEAD_RESOLVED,
                deadLetterMapper.selectById(deadLetter.getId()).getStatus());
        assertInventoryReleased(fixture);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
        verify(firstDelivery).basicAck(11L, false);
        verify(duplicateDelivery).basicAck(12L, false);
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

    @Test
    void concurrentStaleScannersShouldTransitionCreateOnlyOnceAndRestoreManualReview()
            throws Exception {
        Fixture fixture = insertFixture(PRE_DEDUCTED.getCode());
        MqDeadLetterEntity deadLetter = insertCreateDeadLetter(
                fixture,
                DEAD_REPLAYING,
                LocalDateTime.now().minusMinutes(10)
        );
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(5);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> scans = List.of(
                    executor.submit(() -> scanAfterBarrier(deadline, ready, start)),
                    executor.submit(() -> scanAfterBarrier(deadline, ready, start))
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int changed = 0;
            for (Future<Integer> scan : scans) {
                changed += scan.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, changed,
                    "两个扫描器只能有一个通过状态 CAS 改变该死信");
        } finally {
            executor.shutdownNow();
        }

        MqDeadLetterEntity actual = deadLetterMapper.selectById(deadLetter.getId());
        StockDeductRecordEntity record = findDeductRecord(fixture.deductNo());
        assertEquals(DEAD_PENDING, actual.getStatus());
        assertEquals("重放结果确认超时", actual.getLastError());
        assertEquals(MANUAL_REVIEW.getCode(), record.getStatus());
        assertEquals("订单创建死信重放确认超时", record.getLastError());
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

    private int scanAfterBarrier(
            LocalDateTime deadline,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return deadLetterService.recoverStaleReplaying(deadline, 100);
    }

    private Fixture insertFixture() {
        return insertFixture(ORDER_CREATED.getCode());
    }

    private Fixture insertFixture(int deductStatus) {
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
        record.setStatus(deductStatus);
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
        // PRE_DEDUCTED 表示订单创建结果仍未决；只有 ORDER_CREATED 场景才已进入 RESERVED。
        request.setOrderStatus(deductStatus == PRE_DEDUCTED.getCode()
                ? null
                : RESERVED.getCode());
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

    private OrderCreateResultMessage failedResultMessage(Fixture fixture) {
        OrderCreateResultMessage message = new OrderCreateResultMessage();
        message.setMessageId("M05-RESULT-" + UUID.randomUUID());
        message.setTraceId("M05-TRACE-RESULT-" + UUID.randomUUID());
        message.setEventType(ORDER_CREATE_FAILED);
        message.setOccurredAt(LocalDateTime.now());
        message.setRequestId(fixture.requestId());
        message.setDeductNo(fixture.deductNo());
        message.setOrderNo(fixture.orderNo());
        message.setSuccess(false);
        message.setErrorMessage("OrderCare injected order-create failure");
        return message;
    }

    private Channel consumeState(OrderStateChangedMessage message, long deliveryTag)
            throws Exception {
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        stateConsumer.consume(
                new Message(objectMapper.writeValueAsBytes(message), properties),
                channel
        );
        return channel;
    }

    private Channel consumeResult(OrderCreateResultMessage message, long deliveryTag)
            throws Exception {
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        resultConsumer.consume(
                new Message(objectMapper.writeValueAsBytes(message), properties),
                channel
        );
        return channel;
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

    private MqDeadLetterEntity insertResultDeadLetter(
            OrderCreateResultMessage message,
            int status,
            LocalDateTime replayedAt
    ) throws Exception {
        return insertDeadLetter(
                message.getMessageId(),
                ORDER_RESULT_DLQ,
                ORDER_SERVICE,
                message.getEventType(),
                message.getDeductNo(),
                ORDER_RESULT_EXCHANGE,
                ORDER_RESULT_ROUTING_KEY,
                objectMapper.writeValueAsString(message),
                status,
                replayedAt
        );
    }

    private MqDeadLetterEntity insertCreateDeadLetter(
            Fixture fixture,
            int status,
            LocalDateTime replayedAt
    ) {
        return insertDeadLetter(
                "M05-CREATE-" + UUID.randomUUID(),
                ORDER_CREATE_DLQ,
                RESOURCE_SERVICE,
                ORDER_CREATE_COMMAND,
                fixture.deductNo(),
                ORDER_CREATE_EXCHANGE,
                ORDER_CREATE_ROUTING_KEY,
                "{\"fixture\":\"ordercare-m0.5\"}",
                status,
                replayedAt
        );
    }

    private MqDeadLetterEntity insertDeadLetter(
            String messageId,
            String deadQueue,
            String producerService,
            String messageType,
            String bizKey,
            String exchangeName,
            String routingKey,
            String content,
            int status,
            LocalDateTime replayedAt
    ) {
        LocalDateTime now = LocalDateTime.now();
        MqDeadLetterEntity deadLetter = new MqDeadLetterEntity();
        deadLetter.setMessageId(messageId);
        deadLetter.setDeadQueue(deadQueue);
        deadLetter.setProducerService(producerService);
        deadLetter.setMessageType(messageType);
        deadLetter.setBizKey(bizKey);
        deadLetter.setExchangeName(exchangeName);
        deadLetter.setRoutingKey(routingKey);
        deadLetter.setContent(content);
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
        assertInventoryReleased(fixture);
        ReservationRequestEntity request =
                reservationRequestMapper.selectById(fixture.reservationRequestId());
        assertEquals(TIMEOUT.getCode(), request.getOrderStatus());
        assertEquals(ORDER_TIMEOUT, request.getLatestOrderEventType());
    }

    private void assertInventoryReleased(Fixture fixture) {
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        StockDeductRecordEntity record = findDeductRecord(fixture.deductNo());
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId,
                                fixture.stockItemId())
                        .eq(UserReservationQuotaEntity::getUserId, fixture.userId())
        );

        assertEquals(RELEASED.getCode(), record.getStatus());
        assertEquals(10, stock.getAvailableStock());
        assertEquals(0, stock.getLockedStock());
        assertEquals(0, stock.getSoldStock());
        assertEquals(stock.getTotalStock(),
                stock.getAvailableStock()
                        + stock.getLockedStock()
                        + stock.getSoldStock());
        assertEquals(0, quota.getUsedQuantity());
    }

    private StockDeductRecordEntity findDeductRecord(String deductNo) {
        return deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, deductNo)
        );
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
