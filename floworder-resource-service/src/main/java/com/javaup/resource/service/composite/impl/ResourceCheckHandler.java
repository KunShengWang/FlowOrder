package com.javaup.resource.service.composite.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ResourceEntity;
import com.javaup.resource.mapper.ResourceMapper;
import com.javaup.resource.service.composite.AbstractResourceCheckHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 校验资源是否存在、是否启用
 */
@Component
public class ResourceCheckHandler extends AbstractResourceCheckHandler {

    @Resource
    private ResourceMapper resourceMapper;

    @Override
    protected void execute(ResourceOrderCreateDto createDto) {
        Long resourceId = createDto.getResourceId();

        ResourceEntity resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getDeleted() == 1) {
            throw new BizException("资源不存在");
        }
        if (resource.getStatus() != 1) {
            throw new BizException("资源未启用");
        }
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
        return 20;
    }
}
