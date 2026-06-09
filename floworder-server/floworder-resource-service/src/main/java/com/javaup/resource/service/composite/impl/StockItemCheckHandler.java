package com.javaup.resource.service.composite.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.service.composite.AbstractResourceCheckHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 校验库存项是否存在、是否属于该资源、是否启用
 */
@Component
public class StockItemCheckHandler extends AbstractResourceCheckHandler {

    @Resource
    private StockItemMapper stockItemMapper;

    @Override
    protected void execute(ResourceOrderCreateDto createDto) {
        Long stockItemId = createDto.getStockItemId();
        StockItemEntity stockItem = stockItemMapper.selectById(stockItemId);
        if (stockItem == null || stockItem.getDeleted() == 1) {
            throw new BizException("库存项不存在");
        }
        if (!stockItem.getResourceId().equals(createDto.getResourceId())) {
            throw new BizException("库存项不属于当前资源");
        }
        if (stockItem.getStatus() != 1) {
            throw new BizException("库存项未启用");
        }
    }

    @Override
    public Integer executeParentOrder() {
        return 20;
    }

    @Override
    public Integer executeTier() {
        return 3;
    }

    @Override
    public Integer executeOrder() {
        return 40;
    }
}
