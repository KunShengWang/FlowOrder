package com.javaup.resource.service.composite.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.service.composite.AbstractResourceCheckHandler;
import org.springframework.stereotype.Component;

@Component
public class IdempotentCheckHandler extends AbstractResourceCheckHandler {

    @Override
    protected void execute(ResourceOrderCreateDto param) {
        // V1 可以先简单查 requestId 是否已经创建过订单
        // 如果你还没接 order-service 查询接口，这里可以先空实现或只做 Redis 防重复
    }

    @Override
    public Integer executeParentOrder() {
        return 10;
    }

    @Override
    public Integer executeTier() {
        return 2;
    }

    @Override
    public Integer executeOrder() {
        return 30;
    }
}
