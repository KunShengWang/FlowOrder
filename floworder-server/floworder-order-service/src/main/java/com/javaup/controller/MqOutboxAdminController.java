package com.javaup.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.MqOutboxAdminDto;
import com.javaup.mq.service.MqOutboxService;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/mq/outbox")
@ConditionalOnProperty(
        prefix = "floworder.admin",
        name = "enabled",
        havingValue = "true"
)
public class MqOutboxAdminController {

    @Resource
    private MqOutboxService outboxService;

    @GetMapping("/dead")
    public ApiResponse<List<MqOutboxAdminDto>> findDead(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.success(outboxService.findDead(limit));
    }

    @PostMapping("/dead/{messageId}/retry")
    public ApiResponse<Void> retryDead(
            @PathVariable("messageId") String messageId) {
        outboxService.retryDead(messageId);
        return ApiResponse.success();
    }

    @PostMapping("/sent/{messageId}/replay")
    public ApiResponse<Void> replaySent(
            @PathVariable("messageId") String messageId) {
        outboxService.replaySent(messageId);
        return ApiResponse.success();
    }

    @PostMapping("/consumer-dead/{messageId}/replay")
    public ApiResponse<Void> replayConsumerDead(
            @PathVariable("messageId") String messageId) {
        outboxService.replayConsumerDead(messageId);
        return ApiResponse.success();
    }
}
