package com.javaup.initialize.execute;

import com.javaup.initialize.constant.InitializeHandlerType;
import com.javaup.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 用于处理 {@link ApplicationStartedEvent} 应用程序启动事件。
 */
public class ApplicationStartEventListenerExecute extends AbstractApplicationExecute implements ApplicationListener<ApplicationStartedEvent> {

    public ApplicationStartEventListenerExecute(ConfigurableApplicationContext applicationContext){
        super(applicationContext);
    }

    /**
     * ApplicationListener 应用启动完成事件触发 onApplicationEvent()
     */
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        execute();
    }

    @Override
    public String type() {
        return InitializeHandlerType.APPLICATION_EVENT_LISTENER;
    }
}
