package com.javaup.client;

import com.javaup.common.ApiResponse;
import com.javaup.constant.Constant;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(
        value = Constant.FLOWORDER_ORDER_SERVICE,
        contextId = "orderMqAdminClient"
)
public interface OrderMqAdminClient {

    @PostMapping("/internal/mq/outbox/consumer-dead/{messageId}/replay")
    ApiResponse<Void> replayConsumerDead(@PathVariable("messageId") String messageId);
}