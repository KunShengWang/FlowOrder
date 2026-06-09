package com.javaup.initialize.config;

import com.javaup.initialize.execute.ApplicationStartEventListenerExecute;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 初始化执行 相关配置
 */
public class InitializeAutoConfig {

    @Bean
    public ApplicationStartEventListenerExecute applicationStartEventListenerExecute(ConfigurableApplicationContext applicationContext){
        return new ApplicationStartEventListenerExecute(applicationContext);
    }
}
