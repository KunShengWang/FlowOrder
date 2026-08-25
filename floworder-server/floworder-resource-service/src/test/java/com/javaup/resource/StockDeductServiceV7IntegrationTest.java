package com.javaup.resource;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.ResourceEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.ResourceMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.PRE_DEDUCTED;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.compensation.enabled=false",
        "floworder.instant.enabled=false",
        "floworder.v8.enabled=false",
        "floworder.admin.enabled=false"
})
class StockDeductServiceV7IntegrationTest {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private StockDeductService stockDeductService;

    @Resource
    private ResourceMapper resourceMapper;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private UserReservationQuotaMapper quotaMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private MqOutboxMapper outboxMapper;

    @Resource
    private ReservationRequestMapper requestMapper;

    private final List<Long> resourceIds = new ArrayList<>();
    private final List<Long> stockItemIds = new ArrayList<>();
    private final List<Long> quotaIds = new ArrayList<>();
    private final List<String> deductNos = new ArrayList<>();
    private final List<String> messageIds = new ArrayList<>();
    private final List<String> requestIds = new ArrayList<>();

    @AfterEach
    void cleanData() {
        for (String messageId : messageIds) {
            outboxMapper.delete(
                    Wrappers.<MqOutboxEntity>lambdaQuery()
                            .eq(MqOutboxEntity::getMessageId, messageId)
            );
        }
        for (String deductNo : deductNos) {
            deductRecordMapper.delete(
                    Wrappers.<StockDeductRecordEntity>lambdaQuery()
                            .eq(StockDeductRecordEntity::getDeductNo, deductNo)
            );
        }
        for (String requestId : requestIds) {
            requestMapper.delete(
                    Wrappers.<ReservationRequestEntity>lambdaQuery()
                            .eq(ReservationRequestEntity::getRequestId, requestId)
            );
        }
        quotaIds.forEach(quotaMapper::deleteById);
        stockItemIds.forEach(stockItemMapper::deleteById);
        resourceIds.forEach(resourceMapper::deleteById);
    }

    @Test
    void successShouldCommitQuotaStockRecordAndOutboxTogether() {
        Fixture fixture = insertFixture(10, 5, false);
        RequestData data = requestData(fixture, 2);

        stockDeductService.preDeductAndSaveOutbox(
                data.dto(), data.record(), data.outbox()
        );

        assertState(fixture, 2, 8, 2, 1, 1);
    }

    @Test
    void instantAcceptanceShouldCommitBusinessDataButKeepOrderStatusNull() {
        Fixture fixture = insertFixture(10, 5, false);
        RequestData data = requestData(fixture, 2);
        ReservationRequestEntity request = insertProcessingRequest(data.dto(), "owner-1");

        stockDeductService.preDeductAndSaveOutboxAndAcceptRequest(
                data.dto(),
                data.record(),
                data.outbox(),
                request.getId(),
                "owner-1"
        );

        assertState(fixture, 2, 8, 2, 1, 1);
        ReservationRequestEntity accepted = requestMapper.selectById(request.getId());
        assertNotNull(accepted);
        assertEquals(20, accepted.getStatus());
        assertEquals(data.record().getOrderNo(), accepted.getOrderNo());
        assertNull(accepted.getOrderStatus(), "订单结果消息到达前order_status必须为空");
    }

    @Test
    void expiredWindowShouldRollbackQuotaAndDeductRecord() {
        Fixture fixture = insertFixture(10, 5, true);
        RequestData data = requestData(fixture, 2);

        BizException exception = assertThrows(
                BizException.class,
                () -> stockDeductService.preDeductAndSaveOutbox(
                        data.dto(), data.record(), data.outbox())
        );

        assertEquals("资源不可预约或MySQL库存不足", exception.getMessage());
        assertState(fixture, 0, 10, 0, 0, 0);
    }

    @Test
    void duplicateOutboxMessageShouldRollbackQuotaStockAndDeductRecord() {
        Fixture fixture = insertFixture(10, 5, false);
        RequestData data = requestData(fixture, 2);

        MqOutboxEntity existing = outbox(
                data.outbox().getMessageId(),
                "existing-" + UUID.randomUUID()
        );
        assertEquals(1, outboxMapper.insert(existing));

        assertThrows(
                DuplicateKeyException.class,
                () -> stockDeductService.preDeductAndSaveOutbox(
                        data.dto(), data.record(), data.outbox())
        );

        assertState(fixture, 0, 10, 0, 0, 1);
    }

    @Test
    void concurrentRequestsShouldNeverExceedUserQuota() throws Exception {
        Fixture fixture = insertFixture(10, 5, false);
        int taskCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                RequestData data = requestData(fixture, 1);
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        stockDeductService.preDeductAndSaveOutbox(
                                data.dto(), data.record(), data.outbox()
                        );
                        successes.incrementAndGet();
                    } catch (BizException exception) {
                        rejected.incrementAndGet();
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(unexpected.isEmpty(), () -> "unexpected=" + unexpected);
        assertEquals(5, successes.get());
        assertEquals(5, rejected.get());
        assertState(fixture, 5, 5, 5, 5, 5);
    }

    private Fixture insertFixture(int stock, int quotaLimit, boolean expired) {
        String suffix = UUID.randomUUID().toString();
        long resourceId = positiveId();
        long stockItemId = positiveId();
        long userId = positiveId();
        LocalDateTime now = LocalDateTime.now();

        ResourceEntity resource = new ResourceEntity();
        resource.setId(resourceId);
        resource.setResourceCode("V7-RESOURCE-" + suffix);
        resource.setName("V7 integration resource");
        resource.setDescription("V7 transaction test");
        resource.setStatus(1);
        resource.setDeleted(0);
        resource.setCreatedAt(now);
        resource.setUpdatedAt(now);
        assertEquals(1, resourceMapper.insert(resource));
        resourceIds.add(resourceId);

        StockItemEntity stockItem = new StockItemEntity();
        stockItem.setId(stockItemId);
        stockItem.setStockItemCode("V7-STOCK-" + suffix);
        stockItem.setResourceId(resourceId);
        stockItem.setName("V7 integration stock");
        stockItem.setTotalStock(stock);
        stockItem.setAvailableStock(stock);
        stockItem.setLockedStock(0);
        stockItem.setSoldStock(0);
        stockItem.setStatus(1);
        stockItem.setStartTime(now.minusHours(2));
        stockItem.setEndTime(expired ? now.minusHours(1) : now.plusHours(1));
        stockItem.setVersion(0);
        stockItem.setDeleted(0);
        stockItem.setCreatedAt(now);
        stockItem.setUpdatedAt(now);
        assertEquals(1, stockItemMapper.insert(stockItem));
        stockItemIds.add(stockItemId);

        UserReservationQuotaEntity quota = new UserReservationQuotaEntity();
        quota.setResourceId(resourceId);
        quota.setStockItemId(stockItemId);
        quota.setUserId(userId);
        quota.setStatus(1);
        quota.setLimitQuantity(quotaLimit);
        quota.setUsedQuantity(0);
        quota.setValidFrom(now.minusHours(1));
        quota.setValidUntil(now.plusHours(1));
        quota.setVersion(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        assertEquals(1, quotaMapper.insert(quota));
        quotaIds.add(quota.getId());

        return new Fixture(resourceId, stockItemId, userId, stock, quotaLimit);
    }

    private RequestData requestData(Fixture fixture, int quantity) {
        String suffix = UUID.randomUUID().toString();
        String deductNo = "V7-DEDUCT-" + suffix;
        String messageId = "V7-MESSAGE-" + suffix;
        LocalDateTime now = LocalDateTime.now();
        deductNos.add(deductNo);
        messageIds.add(messageId);

        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setResourceId(fixture.resourceId());
        dto.setStockItemId(fixture.stockItemId());
        dto.setUserId(fixture.userId());
        dto.setQuantity(quantity);
        dto.setRequestId("V7-REQUEST-" + suffix);
        requestIds.add(dto.getRequestId());

        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setDeductNo(deductNo);
        record.setOrderNo("V7-ORDER-" + suffix);
        record.setUserId(fixture.userId());
        record.setResourceId(fixture.resourceId());
        record.setStockItemId(fixture.stockItemId());
        record.setQuantity(quantity);
        record.setRequestId(dto.getRequestId());
        record.setStatus(PRE_DEDUCTED.getCode());
        record.setExpireTime(now.plusMinutes(15));
        record.setRetryCount(0);
        record.setQueryErrorCount(0);
        record.setCreateMode(3);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        return new RequestData(dto, record, outbox(messageId, deductNo));
    }

    private MqOutboxEntity outbox(String messageId, String bizKey) {
        LocalDateTime now = LocalDateTime.now();
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(messageId);
        outbox.setProducerService(RESOURCE_SERVICE);
        outbox.setBizKey(bizKey);
        outbox.setMessageType(ORDER_CREATE_COMMAND);
        outbox.setExchangeName(ORDER_CREATE_EXCHANGE);
        outbox.setRoutingKey(ORDER_CREATE_ROUTING_KEY);
        outbox.setContent("{}");
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private ReservationRequestEntity insertProcessingRequest(
            ResourceOrderCreateDto dto,
            String owner
    ) {
        LocalDateTime now = LocalDateTime.now();
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setRequestId(dto.getRequestId());
        request.setTraceId("trace-" + dto.getRequestId());
        request.setUserId(dto.getUserId());
        request.setResourceId(dto.getResourceId());
        request.setStockItemId(dto.getStockItemId());
        request.setQuantity(dto.getQuantity());
        request.setProcessingMode(1);
        request.setStatus(10);
        request.setOrderStatus(null);
        request.setOrderEventVersion(0);
        request.setRetryCount(0);
        request.setClaimOwner(owner);
        request.setClaimUntil(now.plusSeconds(30));
        request.setStartedAt(now);
        request.setVersion(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        assertEquals(1, requestMapper.insert(request));
        return request;
    }

    private void assertState(
            Fixture fixture,
            int usedQuota,
            int available,
            int locked,
            long deductCount,
            long outboxCount) {
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId,
                                fixture.stockItemId())
                        .eq(UserReservationQuotaEntity::getUserId,
                                fixture.userId())
        );
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        long records = deductRecordMapper.selectCount(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getStockItemId,
                                fixture.stockItemId())
        );
        long outboxes = outboxMapper.selectCount(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .in(MqOutboxEntity::getMessageId, messageIds)
        );

        assertNotNull(quota);
        assertNotNull(stock);
        assertEquals(usedQuota, quota.getUsedQuantity());
        assertEquals(available, stock.getAvailableStock());
        assertEquals(locked, stock.getLockedStock());
        assertEquals(fixture.totalStock(),
                stock.getAvailableStock() + stock.getLockedStock() + stock.getSoldStock());
        assertTrue(quota.getUsedQuantity() >= 0);
        assertTrue(quota.getUsedQuantity() <= fixture.quotaLimit());
        assertEquals(deductCount, records);
        assertEquals(outboxCount, outboxes);
    }

    private long positiveId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    private record Fixture(
            Long resourceId,
            Long stockItemId,
            Long userId,
            Integer totalStock,
            Integer quotaLimit) {
    }

    private record RequestData(
            ResourceOrderCreateDto dto,
            StockDeductRecordEntity record,
            MqOutboxEntity outbox) {
    }
}
