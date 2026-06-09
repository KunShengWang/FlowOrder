package com.javaup.resource.service.composite;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.CompositeCheckTypeEnum;
import com.javaup.initialize.impl.composite.AbstractComposite;

/**
 * 生成资源订单验证基类，生成资源订单的相关验证逻辑继承此类
 **/
public abstract class AbstractResourceCheckHandler extends AbstractComposite<ResourceOrderCreateDto> {

    /**
     * 获取返回组件的类型
     */
    @Override
    public String type(){
        return CompositeCheckTypeEnum.PROGRAM_ORDER_CREATE_CHECK.getValue();
    }
}
