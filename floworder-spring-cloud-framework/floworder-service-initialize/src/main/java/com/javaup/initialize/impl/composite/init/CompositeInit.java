package com.javaup.initialize.impl.composite.init;

import com.javaup.initialize.base.AbstractApplicationStartEventListenerHandler;
import com.javaup.initialize.impl.composite.CompositeContainer;
import lombok.AllArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 组合模式初始化操作执行
 */
@AllArgsConstructor
public class CompositeInit extends AbstractApplicationStartEventListenerHandler {

    private final CompositeContainer compositeContainer;

    @Override
    public Integer executeOrder() {
        return 1;
    }

    @Override
    public void executeInit(ConfigurableApplicationContext context) {
        compositeContainer.init(context);
    }
}
