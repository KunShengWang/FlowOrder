package com.javaup.resource;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan({"com.javaup.resource.mapper"})
@SpringBootApplication(scanBasePackages = {"com.javaup"})
@EnableFeignClients(basePackages = {"com.javaup.client"})
@Slf4j
public class FloworderResourceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloworderResourceServiceApplication.class, args);
        log.info("资源服务启动成功！");
    }

}
