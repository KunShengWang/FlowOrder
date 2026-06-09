package com.javaup.initialize.impl.composite;

import com.javaup.initialize.impl.composite.init.CompositeInit;
import org.springframework.context.annotation.Bean;

public class CompositeAutoConfiguration {

    /**
     * 这个Bean只是给下面的Bean服务的，让spring能自动的把CompositeContainer传到compositeInit()方法里
     */
    @Bean
    public CompositeContainer compositeContainer(){
        return new CompositeContainer();
    }

    @Bean
    public CompositeInit compositeInit(CompositeContainer compositeContainer){
        return new CompositeInit(compositeContainer);
    }
}
