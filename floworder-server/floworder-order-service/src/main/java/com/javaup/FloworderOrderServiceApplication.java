package com.javaup;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({"com.javaup.mapper"})
@SpringBootApplication
@EnableFeignClients(basePackages = {"com.javaup.client"})
@EnableDiscoveryClient
@EnableScheduling
@Slf4j
public class FloworderOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloworderOrderServiceApplication.class, args);
        log.info("订单服务启动成功！");
    }

}
