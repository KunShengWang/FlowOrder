package com.javaup.mq.task;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.publisher.OrderResultOutboxPublisher;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderResultOutboxPublishTask {

    @Resource
    private MqOutboxService outboxService;

    @Resource
    private OrderResultOutboxPublisher publisher;

    @Scheduled(fixedDelayString = "${floworder.mq.outbox-delay:1000}")
    public void publish() {
        outboxService.reclaimExpiredClaims();

        for (MqOutboxEntity record : outboxService.findSendable(100)) {

            if (!outboxService.claim(record.getId())) {
                continue;
            }

            try {
                publisher.publish(record);
            } catch (RuntimeException exception) {
                log.error(
                        "订单结果Outbox投递异常, messageId={}",
                        record.getMessageId(),
                        exception
                );
            }
        }
    }
}