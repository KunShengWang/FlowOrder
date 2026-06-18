package com.javaup.resource.mq.task;

import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.publisher.OutboxMessagePublisher;
import com.javaup.resource.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "floworder.mq.outbox-publish-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MqOutboxPublishTask {

    @Resource
    private MqOutboxService mqOutboxService;

    @Resource
    private OutboxMessagePublisher publisher;

    @Scheduled(
        fixedDelayString = "${floworder.mq.outbox-delay:1000}",
        scheduler = "resourceOutboxTaskScheduler"
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
