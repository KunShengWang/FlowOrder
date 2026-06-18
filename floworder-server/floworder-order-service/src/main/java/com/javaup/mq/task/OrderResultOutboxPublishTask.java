package com.javaup.mq.task;

import com.javaup.entity.MqOutboxEntity;
import com.javaup.mq.publish.OrderResultOutboxPublisher;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "floworder.mq.outbox-publish-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class OrderResultOutboxPublishTask {

    @Resource
    private MqOutboxService mqOutboxService;

    @Resource
    private OrderResultOutboxPublisher publisher;

    @Scheduled(
            fixedDelayString = "${floworder.mq.outbox-delay:1000}",
            scheduler = "orderOutboxTaskScheduler"
    )
    public void publish(){
        // MQ Outbox 消息发送租约回收
        mqOutboxService.reclaimExpiredClaims();
        // 查询可发送的消息
        List<MqOutboxEntity> records = mqOutboxService.findSendable(100);
        for (MqOutboxEntity record : records) {
            // 抢占消息
            if(!mqOutboxService.claims(record.getId())){
                continue;
            }
            try{
                publisher.publish(record);
            } catch (RuntimeException e) {
                log.error("消息投递失败,id = {},messageId = {}",record.getId(),record.getMessageId(),e);
            }
        }
    }
}
