package com.javaup.resource.mq.task;

import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.mq.publisher.OutboxMessagePublisher;
import com.javaup.resource.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class MqOutboxPublishTask {

    @Resource
    private MqOutboxService mqOutboxService;

    @Resource
    private OutboxMessagePublisher publisher;

    @Scheduled(fixedDelayString = "${floworder.mq.outbox-delay:1000}")
    public void publish(){
        // MQ Outbox 消息发送租约回收
        mqOutboxService.reclaimExpiredClaims();
        // 找到可发送的消息
        List<MqOutboxEntity> records = mqOutboxService.findSendable(100);
        for (MqOutboxEntity record : records) {
            // 多实例间抢占消息，在单实例中这个抢占是没必要的，但是在多实例中可能会出现RabbitMQ 就会收到两条相同消息。
            if(!mqOutboxService.claim(record.getId())){
                continue;
            }
            try{
                publisher.publish(record);
            } catch (RuntimeException exception) {
                log.error(
                        "Outbox投递任务异常, id={}, messageId={}",
                        record.getId(),
                        record.getMessageId(),
                        exception
                );
            }
        }
    }
}
