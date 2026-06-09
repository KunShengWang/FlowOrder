package com.javaup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

/**
 * redisson属性配置
 * 写在引用这个公共模块的启动服务模块的 application.yml 中。
 **/
@Data
@ConfigurationProperties(prefix = "spring.redisson")
public class RedissonBaseProperties {

    private String address;
    
    private Integer database;
}
