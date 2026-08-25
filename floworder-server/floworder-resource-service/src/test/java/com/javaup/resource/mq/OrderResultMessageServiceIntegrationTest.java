package com.javaup.resource.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.OrderCreateResultMessage;
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
import com.javaup.resource.mq.service.OrderResultMessageService;
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
class OrderResultMessageServiceIntegrationTest {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderResultMessageService messageService;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private UserReservationQuotaMapper quotaMapper;

    @Resource
    private MqConsumeLogMapper consumeLogMapper;

    @Resource
    private ReservationRequestMapper requestMapper;

    private final List<Long> stockItemIds = new ArrayList<>();
    private final List<Long> quotaIds = new ArrayList<>();
    private final List<String> deductNos = new ArrayList<>();

    @AfterEach
    void cleanData() {
        for (String deductNo : deductNos) {
            consumeLogMapper.delete(
                    Wrappers.<MqConsumeLogEntity>lambdaQuery()
                            .eq(MqConsumeLogEntity::getBizKey, deductNo)
            );
            requestMapper.delete(
                    Wrappers.<ReservationRequestEntity>lambdaQuery()
                            .eq(ReservationRequestEntity::getRequestId,
                                    requestIdByDeductNo(deductNo))
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
    void failedResultShouldReleaseStockAndQuotaOnlyOnce() {
        Fixture fixture = insertFixture(PRE_DEDUCTED.getCode());
        OrderCreateResultMessage message = resultMessage(fixture, false);

        assertEquals(fixture.stockItemId(), messageService.handle(message));
        assertEquals(fixture.stockItemId(), messageService.handle(message));

        assertState(fixture, 10, 0, 0, RELEASED.getCode());
        assertRequestState(fixture, 40, 50);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void successfulResultShouldKeepQuotaAndMarkOrderCreated() {
        Fixture fixture = insertFixture(PRE_DEDUCTED.getCode());
        OrderCreateResultMessage message = resultMessage(fixture, true);

        assertNull(messageService.handle(message));

        assertState(fixture, 7, 3, 3, ORDER_CREATED.getCode());
        assertRequestState(fixture, 20, 10);
        assertEquals(1L, consumeLogCount(fixture.deductNo()));
    }

    @Test
    void failedResultForManualReviewShouldRollbackQuotaReleaseAndConsumeLog() {
        Fixture fixture = insertFixture(MANUAL_REVIEW.getCode());
        OrderCreateResultMessage message = resultMessage(fixture, false);

        assertThrows(IllegalStateException.class, () -> messageService.handle(message));

        assertState(fixture, 7, 3, 3, MANUAL_REVIEW.getCode());
        assertEquals(0L, consumeLogCount(fixture.deductNo()));
    }

    private Fixture insertFixture(int recordStatus) {
        String suffix = UUID.randomUUID().toString();
        long stockItemId = positiveId();
        long userId = positiveId();
        LocalDateTime now = LocalDateTime.now();

        StockItemEntity stock = new StockItemEntity();
        stock.setId(stockItemId);
        stock.setStockItemCode("V7-RESULT-STOCK-" + suffix);
        stock.setResourceId(1L);
        stock.setName("V7 result test stock");
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

        String deductNo = "V7-RESULT-DEDUCT-" + suffix;
        String orderNo = "V7-RESULT-ORDER-" + suffix;
        String requestId = "V7-RESULT-REQUEST-" + suffix;
        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setDeductNo(deductNo);
        record.setOrderNo(orderNo);
        record.setUserId(userId);
        record.setResourceId(1L);
        record.setStockItemId(stockItemId);
        record.setQuantity(3);
        record.setRequestId(requestId);
        record.setStatus(recordStatus);
        record.setExpireTime(now.plusMinutes(10));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setRetryCount(0);
        record.setQueryErrorCount(0);
        record.setCreateMode(3);
        assertEquals(1, deductRecordMapper.insert(record));
        deductNos.add(deductNo);

        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setRequestId(requestId);
        request.setTraceId("trace-" + suffix);
        request.setUserId(userId);
        request.setResourceId(1L);
        request.setStockItemId(stockItemId);
        request.setQuantity(3);
        request.setProcessingMode(1);
        request.setOrderNo(orderNo);
        request.setStatus(20);
        request.setOrderStatus(null);
        request.setOrderEventVersion(0);
        request.setRetryCount(0);
        request.setVersion(0);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        assertEquals(1, requestMapper.insert(request));

        UserReservationQuotaEntity quota = new UserReservationQuotaEntity();
        quota.setResourceId(1L);
        quota.setStockItemId(stockItemId);
        quota.setUserId(userId);
        quota.setStatus(1);
        quota.setLimitQuantity(5);
        quota.setUsedQuantity(3);
        quota.setValidFrom(now.minusHours(1));
        quota.setValidUntil(now.plusHours(1));
        quota.setVersion(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        assertEquals(1, quotaMapper.insert(quota));
        quotaIds.add(quota.getId());

        return new Fixture(stockItemId, userId, deductNo, orderNo, requestId);
    }

    private OrderCreateResultMessage resultMessage(Fixture fixture, boolean success) {
        OrderCreateResultMessage message = new OrderCreateResultMessage();
        message.setMessageId(UUID.randomUUID().toString());
        message.setEventType(success ? ORDER_CREATE_SUCCEEDED : ORDER_CREATE_FAILED);
        message.setOccurredAt(LocalDateTime.now());
        message.setRequestId(fixture.requestId());
        message.setDeductNo(fixture.deductNo());
        message.setOrderNo(success ? fixture.orderNo() : null);
        message.setSuccess(success);
        message.setErrorMessage(success ? null : "order rejected");
        return message;
    }

    private void assertState(
            Fixture fixture,
            int available,
            int locked,
            int usedQuota,
            int recordStatus) {
        StockItemEntity stock = stockItemMapper.selectById(fixture.stockItemId());
        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, fixture.deductNo())
        );
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId,
                                fixture.stockItemId())
                        .eq(UserReservationQuotaEntity::getUserId,
                                fixture.userId())
        );

        assertNotNull(stock);
        assertNotNull(record);
        assertNotNull(quota);
        assertEquals(available, stock.getAvailableStock());
        assertEquals(locked, stock.getLockedStock());
        assertEquals(10,
                stock.getAvailableStock() + stock.getLockedStock() + stock.getSoldStock());
        assertEquals(usedQuota, quota.getUsedQuantity());
        assertEquals(recordStatus, record.getStatus());
    }

    private long consumeLogCount(String deductNo) {
        return consumeLogMapper.selectCount(
                Wrappers.<MqConsumeLogEntity>lambdaQuery()
                        .eq(MqConsumeLogEntity::getBizKey, deductNo)
        );
    }

    private void assertRequestState(Fixture fixture, int requestStatus, int orderStatus) {
        ReservationRequestEntity request = requestMapper.selectOne(
                Wrappers.<ReservationRequestEntity>lambdaQuery()
                        .eq(ReservationRequestEntity::getRequestId, fixture.requestId())
        );
        assertNotNull(request);
        assertEquals(requestStatus, request.getStatus());
        assertEquals(orderStatus, request.getOrderStatus());
    }

    private String requestIdByDeductNo(String deductNo) {
        StockDeductRecordEntity record = deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getDeductNo, deductNo)
        );
        return record == null ? "__missing__" : record.getRequestId();
    }

    private long positiveId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    private record Fixture(
            Long stockItemId,
            Long userId,
            String deductNo,
            String orderNo,
            String requestId) {
    }
}
