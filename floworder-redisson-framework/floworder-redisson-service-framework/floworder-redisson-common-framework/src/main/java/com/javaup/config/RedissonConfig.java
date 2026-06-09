package com.javaup.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@EnableConfigurationProperties({RedissonBaseProperties.class})
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedissonBaseProperties properties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(properties.getAddress())
                .setDatabase(properties.getDatabase());
        return Redisson.create(config);
    }
}
