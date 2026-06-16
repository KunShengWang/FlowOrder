package com.javaup.resource.task;

import com.javaup.resource.service.impl.StockDeductCompensationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "floworder.compensation.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StockDeductCompensationTask {

    @Resource
    private StockDeductCompensationService compensationService;

    @Scheduled(
            fixedDelayString = "${floworder.compensation.fixed-delay-ms:5000}",
            initialDelayString = "${floworder.compensation.initial-delay-ms:10000}",
            scheduler = "stockCompensationTaskScheduler"
    )
    public void compensateExpiredDeductRecords() {
        try {
            compensationService.compensateExpiredRecords();
        } catch (RuntimeException e) {
            // 防止整个定时任务因为一次异常停止
            log.error("库存预扣补偿任务执行异常", e);
        }
    }
}
