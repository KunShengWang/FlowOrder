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
import com.javaup.resource.service.ReservationAdmissionService;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class StockDeductServiceImpl implements StockDeductService {

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private MqOutboxMapper mqOutboxMapper;

    @Resource
    private ReservationAdmissionService reservationAdmissionService;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void preDeductAndSaveOutbox(ResourceOrderCreateDto createDto, StockDeductRecordEntity record, MqOutboxEntity outbox) {
        /*
         * 额度和库存使用同一个时间点，避免窗口边界附近出现
         * “额度判断有效但库存窗口判断已经失效”的不一致表达。
         */
        LocalDateTime now = LocalDateTime.now();
        /*
         * 固定共享资源加锁顺序：
         * 用户额度 -> 库存。
         *
         * 后续释放链路也遵循相同顺序，降低占用和释放并发时的死锁概率。
         */
        reservationAdmissionService.reserveQuota(createDto, now);
        /*
         * requestId唯一索引仍然是并发幂等最终防线。
         * 如果这里发生DuplicateKeyException，整个事务会回滚，
         * 前面的额度占用也随事务回滚。
         */
        int recordRows = deductRecordMapper.insert(record);
        if (recordRows != 1) {
            throw new BizException("库存预扣记录保存失败");
        }
        int stockRows = stockItemMapper.preDeductIfAdmissible(
                createDto.getResourceId(),
                createDto.getStockItemId(),
                createDto.getQuantity(),
                now
        );
        if (stockRows != 1) {
            /*
             * 返回0可能是资源关闭、窗口失效、库存项关闭或库存不足。
             * 前置校验负责提供较明确的快速失败提示；
             * 事务最终条件更新只负责保证正确性。
             */
            throw new BizException("资源不可预约或MySQL库存不足");
        }
        int outboxRows = mqOutboxMapper.insert(outbox);
        if (outboxRows != 1) {
            throw new BizException("订单创建消息保存失败");
        }
    }
}
