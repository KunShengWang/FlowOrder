package com.javaup.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class GatewayFlowRuleConfig {

    /**
     * Sentinel自定义API资源名称。
     */
    private static final String ORDER_CREATE_API = "order-create-api";

    /**
     * 单实例每秒允许通过的请求数。
     */
    private static final double ORDER_CREATE_QPS = 10D;

    @PostConstruct
    public void initGatewayRules() {
        initApiDefinitions();
        initFlowRules();
    }

    /**
     * 将V3购买接口定义为一个Sentinel API资源。
     */
    private void initApiDefinitions() {
        ApiPathPredicateItem pathPredicate = new ApiPathPredicateItem()
                        .setPattern("/api/reservation/create/v3")
                        .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT);

        ApiDefinition apiDefinition = new ApiDefinition(ORDER_CREATE_API).setPredicateItems(Set.of(pathPredicate));

        GatewayApiDefinitionManager.loadApiDefinitions(Set.of(apiDefinition));
    }

    /**
     * 对购买接口设置单实例QPS限流。
     */
    private void initFlowRules() {
        GatewayFlowRule flowRule = new GatewayFlowRule(ORDER_CREATE_API)
                        .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                        .setCount(ORDER_CREATE_QPS)
                        .setIntervalSec(1)
                        .setBurst(0);

        GatewayRuleManager.loadRules(Set.of(flowRule));
    }
}