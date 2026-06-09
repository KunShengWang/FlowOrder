package com.javaup.initialize.base;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * 初始化执行 顶级抽象 接口
 */
public interface InitializeHandler {

    /**
     * 初始化执行类型
     */
    String type();

    /**
     * 执行顺序
     */
    Integer executeOrder();

    /**
     * 执行逻辑
     * @param context 容器上下文
     * */
    void executeInit(ConfigurableApplicationContext context);
}
