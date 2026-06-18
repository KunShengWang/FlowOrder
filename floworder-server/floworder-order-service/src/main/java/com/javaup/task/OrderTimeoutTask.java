package com.javaup.task;

import com.javaup.service.OrderStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "floworder.order.timeout-scan-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrderTimeoutTask {

    private static final int BATCH_SIZE = 100;

    @Resource
    private OrderStateService orderStateService;

    @Scheduled(
            fixedDelayString = "${floworder.order.timeout-scan-delay:5000}",
            initialDelayString = "${floworder.order.timeout-scan-initial-delay:10000}",
            scheduler = "orderTimeoutTaskScheduler"
    )
    public void closeExpiredOrders() {
        List<Long> orderIds = orderStateService.findExpiredOrderIds(BATCH_SIZE);
        for (Long orderId : orderIds) {
            try {
                if (orderStateService.timeout(orderId)) {
                    log.info("订单超时关闭成功, orderId={}", orderId);
                }
            } catch (RuntimeException exception) {
                log.error("订单超时关闭失败, orderId={}", orderId, exception);
            }
        }
    }
}