package com.javaup.resource.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenFeign 远程调用熔断规则配置。
 *
 * <p>V5 阶段先用代码方式显式加载规则，避免 YAML 配置 key 和 Feign 资源名不一致导致规则没有生效。</p>
 */
@Configuration
public class FeignDegradeRuleConfig {

    private static final String ORDER_SERVICE_RESOURCE =
            "floworder-order-service";

    @PostConstruct
    public void initFeignDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        rules.add(buildExceptionRatioRule(
                ORDER_SERVICE_RESOURCE,
                0.5D,
                10,
                5
        ));

        DegradeRuleManager.loadRules(rules);
    }

    private DegradeRule buildExceptionRatioRule(
            String resource,
            double exceptionRatio,
            int timeWindowSeconds,
            int minRequestAmount) {

        return new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(exceptionRatio)
                .setTimeWindow(timeWindowSeconds)
                .setMinRequestAmount(minRequestAmount)
                .setStatIntervalMs(10000);
    }
}