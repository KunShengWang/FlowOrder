package com.javaup.initialize.base;

import com.javaup.initialize.constant.InitializeHandlerType;
import org.springframework.context.ApplicationListener;

/**
 * 用于处理 {@link ApplicationListener} 类型 初始化执行 抽象
 **/
public abstract class AbstractApplicationStartEventListenerHandler implements InitializeHandler {

    @Override
    public String type(){
        return InitializeHandlerType.APPLICATION_EVENT_LISTENER;
    }
}
