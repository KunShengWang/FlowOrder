package com.javaup.resource.mq.task;

import com.javaup.resource.mq.service.MqDeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "floworder.mq.dead-letter-monitor.enabled",
        havingValue = "true",
        matchIfMissing = true
)
/**
 * 死信可能已经变成 REPLAYING，但服务突然宕机，永远没有后续结果。
 */
public class MqDeadLetterMonitorTask {

    private final MqDeadLetterService deadLetterService;

    @Value("${floworder.mq.dead-letter-monitor.replay-timeout-seconds:300}")
    private long replayTimeoutSeconds;

    @Scheduled(
        fixedDelayString =
            "${floworder.mq.dead-letter-monitor.fixed-delay-ms:60000}",
        initialDelayString =
            "${floworder.mq.dead-letter-monitor.initial-delay-ms:30000}",
        scheduler = "deadLetterTaskScheduler"
    )
    public void monitor() {
        try {
            // 回收重放超时的死信
            deadLetterService.recoverStaleReplaying(
                    LocalDateTime.now().minusSeconds(replayTimeoutSeconds),
                    100
            );
            // 统计未解决的死信数量，有则告警
            long unresolved = deadLetterService.countUnresolved();
            if (unresolved > 0) {
                log.error("存在未解决MQ消费死信, count={}", unresolved);
            }
        } catch (RuntimeException exception) {
            log.error("MQ消费死信扫描任务执行失败", exception);
        }
    }
}