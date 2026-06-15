package com.javaup.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "floworder.mq.outbox-publish-enabled=false",
        "floworder.compensation.enabled=false",
        "floworder.admin.enabled=false"
})
class FloworderResourceServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
