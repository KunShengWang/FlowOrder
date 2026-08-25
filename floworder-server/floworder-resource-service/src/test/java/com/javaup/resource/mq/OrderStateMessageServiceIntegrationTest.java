package com.javaup.resource.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.OrderStateChangedMessage;
import com.javaup.resource.entity.MqConsumeLogEntity;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import com.javaup.resource.mapper.MqConsumeLogMapper;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.mq.service.OrderStateMessageService;
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

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.enums.OrderStatusEnum.*;
import static com.javaup.resource.enums.StockDeductStatusEnum.*;
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
class OrderStateMessageServiceIntegrationTest {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderStateMessageService messageService;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private MqConsumeLogMapper consumeLogMapper;

    @Resource
    private UserReservationQuotaMapper quotaMapper;

    @Resource
    private ReservationRequestMapper requestMapper;

    private final List<Long> stockItemIds = new ArrayList<>();
    private final List<Long> quotaIds = new ArrayList<>();
    private final List<String> deductNos = new ArrayList<>();
    private final List<Long> requestDbIds = new ArrayList<>();

    @AfterEach
    void cleanData() {
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
        requestDbIds.forEach(requestMapper::deleteById);
        quotaIds.forEach(quotaMapper::deleteById);
        stockItemIds.forEach(stockItemMapper::deleteById);
    }

    @Test
    void confirmShouldMoveLockedStockToSoldAndDuplicateMessageShouldBeIdempotent() {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = stateMessage(fixture, ORDER_CONFIRMED);

        assertNull(messageService.handle(message));
        assertNull(messageService.handle(message));

        assertStock(fixture, 7, 0, 3, SOLD.getCode());
        assertQuota(fixture, 3);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void stateEventBeforeOrderCreatedResultShouldRollbackEverything() {
        Fixture fixture = insertFixture();
        assertEquals(1, requestMapper.update(
                null,
                Wrappers.<ReservationRequestEntity>lambdaUpdate()
                        .eq(ReservationRequestEntity::getId, fixture.requestDbId())
                        .set(ReservationRequestEntity::getOrderStatus, null)
        ));

        assertThrows(
                IllegalStateException.class,
                () -> messageService.handle(stateMessage(fixture, ORDER_CONFIRMED))
        );

        assertStock(fixture, 7, 3, 0, ORDER_CREATED.getCode());
        assertQuota(fixture, 3);
        assertEquals(0L, consumeLogCount(fixture.deductNo()));
        assertNull(requestMapper.selectById(fixture.requestDbId()).getOrderStatus());
    }

    @Test
    void cancelShouldReleaseLockedStockAndDuplicateMessageShouldBeIdempotent() {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = stateMessage(fixture, ORDER_CANCELLED);

        assertEquals(fixture.stockItemId(), messageService.handle(message));
        assertEquals(fixture.stockItemId(), messageService.handle(message));

        assertStock(fixture, 10, 0, 0, RELEASED.getCode());
        assertQuota(fixture, 0);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void cancelAfterConfirmShouldBeRejectedWithoutChangingInventory() {
        Fixture fixture = insertFixture();
        messageService.handle(stateMessage(fixture, ORDER_CONFIRMED));

        assertThrows(
                IllegalStateException.class,
                () -> messageService.handle(stateMessage(fixture, ORDER_CANCELLED))
        );

        assertStock(fixture, 7, 0, 3, SOLD.getCode());
        assertQuota(fixture, 3);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void confirmAfterCancelShouldBeRejectedWithoutChangingInventory() {
        Fixture fixture = insertFixture();
        messageService.handle(stateMessage(fixture, ORDER_TIMEOUT));

        assertThrows(
                IllegalStateException.class,
                () -> messageService.handle(stateMessage(fixture, ORDER_CONFIRMED))
        );

        assertStock(fixture, 10, 0, 0, RELEASED.getCode());
        assertQuota(fixture, 0);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void protocolOrBusinessKeyMismatchShouldNotWriteConsumeLogOrInventory() {
        Fixture fixture = insertFixture();
        OrderStateChangedMessage message = stateMessage(fixture, ORDER_CONFIRMED);
        message.setStockItemId(fixture.stockItemId() + 1);

        assertThrows(IllegalArgumentException.class, () -> messageService.handle(message));

        assertStock(fixture, 7, 3, 0, ORDER_CREATED.getCode());
        assertQuota(fixture, 3);
        assertEquals(0L, consumeLogCount(fixture.deductNo()));
    }

    private Fixture insertFixture() {
        String suffix = UUID.randomUUID().toString();
        long stockItemId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        LocalDateTime now = LocalDateTime.now();

        StockItemEntity stock = new StockItemEntity();
        stock.setId(stockItemId);
        stock.setStockItemCode("V4-STOCK-" + suffix);
        stock.setResourceId(1L);
        stock.setName("V4 test stock");
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

        String deductNo = "V4-DEDUCT-" + suffix;
        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setDeductNo(deductNo);
        record.setOrderNo("V4-ORDER-" + suffix);
        record.setUserId(1L);
        record.setResourceId(1L);
        record.setStockItemId(stockItemId);
        record.setQuantity(3);
        record.setRequestId("V4-REQUEST-" + suffix);
        record.setStatus(ORDER_CREATED.getCode());
        record.setExpireTime(now.plusMinutes(10));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setRetryCount(0);
        record.setQueryErrorCount(0);
        record.setCreateMode(3);
        assertEquals(1, deductRecordMapper.insert(record));
        deductNos.add(deductNo);

        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setRequestId(record.getRequestId());
        request.setTraceId("trace-" + suffix);
        request.setUserId(record.getUserId());
        request.setResourceId(record.getResourceId());
        request.setStockItemId(record.getStockItemId());
        request.setQuantity(record.getQuantity());
        request.setProcessingMode(1);
        request.setOrderNo(record.getOrderNo());
        request.setStatus(20);
        request.setOrderStatus(RESERVED.getCode());
        request.setOrderEventVersion(0);
        request.setRetryCount(0);
        request.setVersion(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        assertEquals(1, requestMapper.insert(request));
        requestDbIds.add(request.getId());

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

        return new Fixture(
                stockItemId,
                deductNo,
                record.getOrderNo(),
                record.getRequestId(),
                request.getId(),
                record.getUserId(),
                3
        );
    }

    private OrderStateChangedMessage stateMessage(Fixture fixture, String eventType) {
        OrderStateChangedMessage message = new OrderStateChangedMessage();
        message.setMessageId(UUID.randomUUID().toString());
        message.setEventType(eventType);
        message.setOrderNo(fixture.orderNo());
        message.setRequestId(fixture.requestId());
        message.setDeductNo(fixture.deductNo());
        message.setStockItemId(fixture.stockItemId());
        message.setQuantity(fixture.quantity());
        message.setFromStatus(RESERVED.getCode());
        if (ORDER_CONFIRMED.equals(eventType)) {
            message.setToStatus(CONFIRMED.getCode());
        } else if (ORDER_CANCELLED.equals(eventType)) {
            message.setToStatus(CANCELLED.getCode());
        } else {
            message.setToStatus(TIMEOUT.getCode());
        }
        message.setOccurredAt(LocalDateTime.now());
        return message;
    }

    private void assertStock(
            Fixture fixture,
            int available,
            int locked,
            int sold,
            int recordStatus) {
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, fixture.deductNo())
        );

        assertEquals(available, stock.getAvailableStock());
        assertEquals(locked, stock.getLockedStock());
        assertEquals(sold, stock.getSoldStock());
        assertEquals(stock.getTotalStock(),
                stock.getAvailableStock() + stock.getLockedStock() + stock.getSoldStock());
        assertTrue(stock.getAvailableStock() >= 0);
        assertTrue(stock.getLockedStock() >= 0);
        assertTrue(stock.getSoldStock() >= 0);
        assertEquals(recordStatus, record.getStatus());
    }

    private Long consumeLogCount(String deductNo) {
        return consumeLogMapper.selectCount(
                Wrappers.<MqConsumeLogEntity>lambdaQuery()
                        .eq(MqConsumeLogEntity::getBizKey, deductNo)
        );
    }

    private void assertQuota(Fixture fixture, int usedQuantity) {
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId,
                                fixture.stockItemId())
                        .eq(UserReservationQuotaEntity::getUserId,
                                fixture.userId())
        );
        assertNotNull(quota);
        assertEquals(usedQuantity, quota.getUsedQuantity());
        assertTrue(quota.getUsedQuantity() >= 0);
        assertTrue(quota.getUsedQuantity() <= quota.getLimitQuantity());
    }

    private record Fixture(
            Long stockItemId,
            String deductNo,
            String orderNo,
            String requestId,
            Long requestDbId,
            Long userId,
            Integer quantity) {
    }
}
