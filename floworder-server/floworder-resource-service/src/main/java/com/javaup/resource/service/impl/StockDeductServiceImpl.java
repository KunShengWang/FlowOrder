package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.MqOutboxMapper;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockDeductServiceImpl implements StockDeductService {

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private MqOutboxMapper mqOutboxMapper;

    /**
     * 库存预扣
     * 1. 插入预扣记录
     * 2. available_stock -> locked_stock
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void preDeduct(ResourceOrderCreateDto dto, StockDeductRecordEntity record) {
        // 插入预扣记录
        deductRecordMapper.insert(record);
        // 更新库存
        int rows = stockItemMapper.update(
                null,
                Wrappers.<StockItemEntity>lambdaUpdate().eq(StockItemEntity::getId, dto.getStockItemId())
                        .eq(StockItemEntity::getStatus, 1)
                        .eq(StockItemEntity::getDeleted, 0)
                        .ge(StockItemEntity::getAvailableStock, dto.getQuantity())
                        .setSql(
                                "available_stock = available_stock - {0}, " +
                                        "locked_stock = locked_stock + {0}, " +
                                        "version = version + 1",
                                dto.getQuantity()
                        )

        );
        if (rows != 1) {
            throw new BizException("MySQL库存不足");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String deductNo, String orderNo) {
        int rows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getDeductNo, deductNo)
                        .eq(StockDeductRecordEntity::getStatus, 10)
                        .set(StockDeductRecordEntity::getOrderNo, orderNo)
                        .set(StockDeductRecordEntity::getStatus, 20)
        );
        if (rows != 1) {
            throw new BizException("确认库存预扣记录失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(ResourceOrderCreateDto dto, String deductNo, String reason) {
        int recordRows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getDeductNo, deductNo)
                        .eq(StockDeductRecordEntity::getStatus, 10)
                        .set(StockDeductRecordEntity::getStatus, 30)
                        .set(StockDeductRecordEntity::getReleaseReason, reason)
        );
        if (recordRows != 1) {
            throw new BizException("预扣记录已经处理，不能重复释放");
        }
        int stockRows = stockItemMapper.update(
                null,
                Wrappers.<StockItemEntity>lambdaUpdate()
                        .eq(StockItemEntity::getId, dto.getStockItemId())
                        .ge(StockItemEntity::getLockedStock, dto.getQuantity())
                        .setSql(
                                "available_stock = available_stock + {0}, " +
                                        "locked_stock = locked_stock - {0}, " +
                                        "version = version + 1",
                                dto.getQuantity()
                        )
        );
        if (stockRows != 1) {
            throw new BizException("释放MySQL库存失败");
        }
    }

    /**
     * 库存预扣并保存mq消息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void preDeductAndSaxveOutbox(ResourceOrderCreateDto createDto, StockDeductRecordEntity record, MqOutboxEntity outbox) {
        preDeduct(createDto,record);
        int rows = mqOutboxMapper.insert(outbox);
        if(rows != 1){
            throw new BizException("订单创建消息保存失败");
        }
    }
}
