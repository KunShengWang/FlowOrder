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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.javaup.enums.OrderStatusEnum.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.order.timeout-scan-enabled=false",
        "floworder.admin.enabled=false"
})
class OrderStateConcurrencyTest {

    @Resource
    private OrderStateService orderStateService;

    @Resource
    private ReservationOrderMapper orderMapper;

    @Resource
    private OrderStatusLogMapper statusLogMapper;

    @Resource
    private MqOutboxMapper outboxMapper;

    private String currentOrderNo;

    @AfterEach
    void cleanData() {
        if (currentOrderNo == null) {
            return;
        }

        statusLogMapper.delete(
                Wrappers.<OrderStatusLogEntity>lambdaQuery()
                        .eq(OrderStatusLogEntity::getOrderNo, currentOrderNo)
        );

        outboxMapper.delete(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getBizKey, currentOrderNo)
        );

        orderMapper.delete(
                Wrappers.<ReservationOrderEntity>lambdaQuery()
                        .eq(ReservationOrderEntity::getOrderNo, currentOrderNo)
        );
    }

    @Test
    void confirmAndCancelShouldOnlyHaveOneWinner() throws Exception {
        ReservationOrderEntity order = insertOrder(LocalDateTime.now().plusMinutes(10));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> confirmTask = () -> {
            ready.countDown();
            start.await();
            try {
                orderStateService.confirm(order.getOrderNo(), order.getUserId());
                return CONFIRMED.getCode();
            } catch (BizException exception) {
                return -1;
            }
        };

        Callable<Integer> cancelTask = () -> {
            ready.countDown();
            start.await();
            try {
                orderStateService.cancel(order.getOrderNo(), order.getUserId(), "并发取消测试");
                return CANCELLED.getCode();
            } catch (BizException exception) {
                return -1;
            }
        };

        try {
            Future<Integer> confirmResult = executor.submit(confirmTask);

            Future<Integer> cancelResult = executor.submit(cancelTask);

            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();

            List<Integer> results = List.of(
                    confirmResult.get(5, TimeUnit.SECONDS),
                    cancelResult.get(5, TimeUnit.SECONDS)
            );

            assertEquals(1, results.stream().filter(status -> status != -1).count());

            assertTerminalResult(order, List.of(CONFIRMED.getCode(), CANCELLED.getCode()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentTimeoutShouldOnlySucceedOnce() throws Exception {
        ReservationOrderEntity order = insertOrder(LocalDateTime.now().minusMinutes(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> task = () -> {
            ready.countDown();
            start.await();
            return orderStateService.timeout(order.getId());
        };

        try {
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);

            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();

            int successCount = 0;
            successCount += first.get(5, TimeUnit.SECONDS) ? 1 : 0;
            successCount += second.get(5, TimeUnit.SECONDS) ? 1 : 0;

            assertEquals(1, successCount);

            assertTerminalResult(order, List.of(TIMEOUT.getCode()));
        } finally {
            executor.shutdownNow();
        }
    }

    private ReservationOrderEntity insertOrder(LocalDateTime expireTime) {

        String suffix = UUID.randomUUID().toString();

        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setOrderNo("TEST-ORDER-" + suffix);
        order.setUserId(1L);
        order.setResourceId(1L);
        order.setStockItemId(1L);
        order.setQuantity(1);
        order.setStatus(RESERVED.getCode());
        order.setRequestId("TEST-REQUEST-" + suffix);
        order.setDeductNo("TEST-DEDUCT-" + suffix);
        order.setExpireTime(expireTime);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.setDeleted(0);

        assertEquals(1, orderMapper.insert(order));

        currentOrderNo = order.getOrderNo();
        return order;
    }

    private void assertTerminalResult(ReservationOrderEntity order, List<Integer> allowedStatuses) {

        ReservationOrderEntity latest = orderMapper.selectById(order.getId());

        assertNotNull(latest);
        assertTrue(allowedStatuses.contains(latest.getStatus()));
        assertEquals(1, latest.getVersion());

        Long logCount = statusLogMapper.selectCount(
                Wrappers.<OrderStatusLogEntity>lambdaQuery()
                        .eq(OrderStatusLogEntity::getOrderNo, order.getOrderNo())
                        .eq(OrderStatusLogEntity::getFromStatus, RESERVED.getCode())
        );

        Long outboxCount = outboxMapper.selectCount(
                Wrappers.<MqOutboxEntity>lambdaQuery()
                        .eq(MqOutboxEntity::getBizKey, order.getOrderNo())
        );

        assertEquals(1L, logCount);
        assertEquals(1L, outboxCount);
    }
}
