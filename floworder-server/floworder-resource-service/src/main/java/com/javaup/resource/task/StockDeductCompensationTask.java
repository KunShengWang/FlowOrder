package com.javaup.resource.task;

import com.javaup.resource.service.impl.StockDeductCompensationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * StockDeductCompensationTask 解决的不是 V8/Instant 的问题，它只处理 V2 同步模式（createMode=2）的卡死预扣记录
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "floworder.compensation.enabled",// 要检查的属性名
        havingValue = "true",// 值必须为 "true" 才启用
        matchIfMissing = true// 如果属性不存在，默认也启用
)
public class StockDeductCompensationTask {

    @Resource
    private StockDeductCompensationService compensationService;

    @Scheduled(
            fixedDelayString = "${floworder.compensation.fixed-delay-ms:5000}",// 	上一次执行结束后，等 5 秒再启动下一次
            initialDelayString = "${floworder.compensation.initial-delay-ms:10000}",// 服务启动后第一次执行延迟 10 秒（给服务初始化留时间）
            scheduler = "stockCompensationTaskScheduler"// 指定线程池 Bean 名，不跟 @Scheduled 默认的公共线程池抢资源
    )
    public void compensateExpiredDeductRecords() {
        try {
            // 库存预扣补偿，扫描"卡在中间态"且 nextRetryTime <= now 的库存预扣记录，以订单为权威做补偿
            // 卡在 PRE_DEDUCTED 的本质是"预扣成功但订单创建结果未知"，可能诱因是 order-service 异常/网络超时导致建单结果不确定
            compensationService.compensateExpiredRecords();
        } catch (RuntimeException e) {
            // 防止整个定时任务因为一次异常停止
            log.error("库存预扣补偿任务执行异常", e);
        }
    }
}
