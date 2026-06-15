package com.javaup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.order.timeout-scan-enabled=false",
        "floworder.admin.enabled=false"
})
class FloworderOrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
