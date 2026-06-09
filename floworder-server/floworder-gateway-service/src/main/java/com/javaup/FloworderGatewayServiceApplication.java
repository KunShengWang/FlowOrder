package com.javaup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@Slf4j
public class FloworderGatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloworderGatewayServiceApplication.class, args);
        log.info("网关层启动成功！");
    }

}
