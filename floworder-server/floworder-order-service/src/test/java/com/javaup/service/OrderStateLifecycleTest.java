package com.javaup.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.entity.MqOutboxEntity;
import com.javaup.entity.OrderStatusLogEntity;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.MqOutboxMapper;
import com.javaup.mapper.OrderStatusLogMapper;
import com.javaup.mapper.ReservationOrderMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.enums.OrderStatusEnum.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.order.timeout-scan-enabled=false",
        "floworder.admin.enabled=false"
})
class OrderStateLifecycleTest {

    @Resource
    private OrderStateService orderStateService;

    @Resource
    private ReservationOrderMapper orderMapper;

    @Resource
    private OrderStatusLogMapper statusLogMapper;

    @Resource
    private MqOutboxMapper outboxMapper;

    private final List<String> orderNos = new ArrayList<>();

    @AfterEach
    void cleanData() {
        for (String orderNo : orderNos) {
            statusLogMapper.delete(
                    Wrappers.<OrderStatusLogEntity>lambdaQuery()
                            .eq(OrderStatusLogEntity::getOrderNo, orderNo)
            );
            outboxMapper.delete(
                    Wrappers.<MqOutboxEntity>lambdaQuery()
                            .eq(MqOutboxEntity::getBizKey, orderNo)
            );
            orderMapper.delete(
                    Wrappers.<ReservationOrderEntity>lambdaQuery()
                            .eq(ReservationOrderEntity::getOrderNo, orderNo)
            );
        }
    }

    @Test
    void repeatedConfirmShouldBeIdempotent() {
        ReservationOrderEntity order = insertOrder(RESERVED.getCode(), LocalDateTime.now().plusMinutes(10));

        orderStateService.confirm(order.getOrderNo(), order.getUserId());
        orderStateService.confirm(order.getOrderNo(), order.getUserId());

        assertStateEffects(order, CONFIRMED.getCode(), 1);
    }

    @Test
    void repeatedCancelShouldBeIdempotentAndConfirmedOrderCannotBeCancelled() {
        ReservationOrderEntity cancelled = insertOrder(RESERVED.getCode(), LocalDateTime.now().plusMinutes(10));

        orderStateService.cancel(cancelled.getOrderNo(), cancelled.getUserId(), "test cancel");
        orderStateService.cancel(cancelled.getOrderNo(), cancelled.getUserId(), "ignored duplicate");

        assertStateEffects(cancelled, CANCELLED.getCode(), 1);

        ReservationOrderEntity confirmed = insertOrder(RESERVED.getCode(), LocalDateTime.now().plusMinutes(10));
        orderStateService.confirm(confirmed.getOrderNo(), confirmed.getUserId());

        assertThrows(
                BizException.class,
                () -> orderStateService.cancel(confirmed.getOrderNo(), confirmed.getUserId(), "invalid")
        );
        assertStateEffects(confirmed, CONFIRMED.getCode(), 1);
    }

    @Test
    void expiredOrderShouldRejectUserOperationsAndAllowTimeoutOnlyOnce() {
        ReservationOrderEntity order = insertOrder(RESERVED.getCode(), LocalDateTime.now().minusMinutes(1));

        assertThrows(
                BizException.class,
                () -> orderStateService.confirm(order.getOrderNo(), order.getUserId())
        );
        assertThrows(
                BizException.class,
                () -> orderStateService.cancel(order.getOrderNo(), order.getUserId(), "too late")
        );

        assertTrue(orderStateService.timeout(order.getId()));
        assertFalse(orderStateService.timeout(order.getId()));
        assertStateEffects(order, TIMEOUT.getCode(), 1);
    }

    @Test
    void expiredOrderQueryShouldIgnoreFutureAndTerminalOrders() {
        ReservationOrderEntity expired = insertOrder(RESERVED.getCode(), LocalDateTime.now().minusMinutes(2));
        ReservationOrderEntity future = insertOrder(RESERVED.getCode(), LocalDateTime.now().plusMinutes(2));
        ReservationOrderEntity terminal = insertOrder(CONFIRMED.getCode(), LocalDateTime.now().minusMinutes(2));

        List<Long> ids = orderStateService.findExpiredOrderIds(500);

        assertTrue(ids.contains(expired.getId()));
        assertFalse(ids.contains(future.getId()));
        assertFalse(ids.contains(terminal.getId()));
    }

    @Test
    void outboxInsertFailureShouldRollbackOrderAndStatusLog() {
        ReservationOrderEntity order = insertOrder(RESERVED.getCode(), LocalDateTime.now().plusMinutes(10));
        insertConflictingOutbox(order.getOrderNo());

        assertThrows(
                RuntimeException.class,
                () -> orderStateService.confirm(order.getOrderNo(), order.getUserId())
        );

        ReservationOrderEntity latest = orderMapper.selectById(order.getId());
        assertEquals(RESERVED.getCode(), latest.getStatus());
        assertEquals(0, latest.getVersion());
        assertEquals(0L, statusLogCount(order.getOrderNo()));
        assertEquals(1L, outboxCount(order.getOrderNo()));
    }

    private ReservationOrderEntity insertOrder(Integer status, LocalDateTime expireTime) {
        String suffix = UUID.randomUUID().toString();
        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setOrderNo("V4-ORDER-" + suffix);
        order.setUserId(1L);
        order.setResourceId(1L);
        order.setStockItemId(1L);
        order.setQuantity(1);
        order.setStatus(status);
        order.setRequestId("V4-REQUEST-" + suffix);
        order.setDeductNo("V4-DEDUCT-" + suffix);
        order.setExpireTime(expireTime);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.setDeleted(0);
        assertEquals(1, orderMapper.insert(order));
        orderNos.add(order.getOrderNo());
        return order;
    }

    private void insertConflictingOutbox(String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(UUID.randomUUID().toString());
        outbox.setProducerService(ORDER_SERVICE);
        outbox.setBizKey(orderNo);
        outbox.setMessageType(ORDER_CONFIRMED);
        outbox.setExchangeName(ORDER_STATE_EXCHANGE);
        outbox.setRoutingKey(ORDER_STATE_ROUTING_KEY);
        outbox.setContent("{}");
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        assertEquals(1, outboxMapper.insert(outbox));
    }

    private void assertStateEffects(
            ReservationOrderEntity order,
            Integer expectedStatus,
            int expectedVersion) {
        ReservationOrderEntity latest = orderMapper.selectById(order.getId());
        assertEquals(expectedStatus, latest.getStatus());
        assertEquals(expectedVersion, latest.getVersion());
        assertEquals(1L, statusLogCount(order.getOrderNo()));
        assertEquals(1L, outboxCount(order.getOrderNo()));
    }

    private Long statusLogCount(String orderNo) {
        return statusLogMapper.selectCount(
                Wrappers.<OrderStatusLogEntity>lambdaQuery()
                        .eq(OrderStatusLogEntity::getOrderNo, orderNo)
        );
    }

    private Long outboxCount(String orderNo) {
        return outboxMapper.selectCount(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getBizKey, orderNo)
        );
    }
}
