package com.javaup.resource.controller;

import com.javaup.common.ApiResponse;
import com.javaup.dto.MqDeadLetterAdminDto;
import com.javaup.resource.mq.service.MqDeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/mq/dead-letter")
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "floworder.admin",
        name = "enabled",
        havingValue = "true"
)
public class MqDeadLetterAdminController {

    private final MqDeadLetterService deadLetterService;

    @GetMapping
    public ApiResponse<List<MqDeadLetterAdminDto>> find(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(deadLetterService.find(status, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<MqDeadLetterAdminDto> findById(@PathVariable Long id) {
        return ApiResponse.success(deadLetterService.findById(id));
    }

    /**
     * 人工重放
     */
    @PostMapping("/{id}/replay")
    public ApiResponse<Void> replay(@PathVariable Long id, @RequestParam String operator) {
        deadLetterService.replay(id, operator);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/ignore")
    public ApiResponse<Void> ignore(
            @PathVariable Long id,
            @RequestParam String operator,
            @RequestParam String reason,
            @RequestParam(defaultValue = "false") boolean force) {
        deadLetterService.ignore(id, operator, reason, force);
        return ApiResponse.success();
    }
}
