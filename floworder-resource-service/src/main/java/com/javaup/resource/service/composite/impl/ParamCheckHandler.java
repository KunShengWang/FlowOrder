package com.javaup.resource.service.composite.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.service.composite.AbstractResourceCheckHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 参数校验
 */
@Component
public class ParamCheckHandler extends AbstractResourceCheckHandler {

    @Override
    protected void execute(ResourceOrderCreateDto createDto) {
        if (createDto.getUserId() == null) {
            throw new BizException("用户ID不能为空");
        }
        if (createDto.getResourceId() == null) {
            throw new BizException("资源ID不能为空");
        }
        if (createDto.getStockItemId() == null) {
            throw new BizException("库存项ID不能为空");
        }
        if (createDto.getQuantity() == null || createDto.getQuantity() <= 0) {
            throw new BizException("预约数量非法");
        }
        if (!StringUtils.hasText(createDto.getRequestId())) {
            throw new BizException("requestId不能为空");
        }
    }

    @Override
    public Integer executeParentOrder() {
        return 0;
    }

    @Override
    public Integer executeTier() {
        return 1;
    }

    @Override
    public Integer executeOrder() {
        return 10;
    }
}
