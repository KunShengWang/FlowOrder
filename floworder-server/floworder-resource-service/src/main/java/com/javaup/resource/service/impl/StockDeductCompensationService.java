package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderQueryDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class StockDeductCompensationService {

    private static final int PRE_DEDUCTED = 10;
    private static final int SUCCESS_CODE = 200;
    private static final int BATCH_SIZE = 100;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private StockDeductService stockDeductService;

    @Resource
    private OrderClient orderClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void compensateExpiredRecords() {
        log.info("开始扫描过期库存预扣记录");
        List<StockDeductRecordEntity> records =
                deductRecordMapper.selectList(
                        Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED)
                                .le(StockDeductRecordEntity::getExpireTime,
                                        LocalDateTime.now())
                                .orderByAsc(StockDeductRecordEntity::getExpireTime)
                                .last("LIMIT " + BATCH_SIZE)
                );
        log.info("本轮扫描到 {} 条待补偿记录", records.size());
        for (StockDeductRecordEntity record : records) {
            try {
                compensateOne(record);
            } catch (RuntimeException e) {
                // 一条失败不能中断整个批次
                log.error(
                        "库存预扣补偿失败, requestId={}, deductNo={}",
                        record.getRequestId(),
                        record.getDeductNo(),
                        e
                );
            }
        }
    }

    private void compensateOne(StockDeductRecordEntity record) {
        ApiResponse<OrderQueryDto> response;
        try {
            response = orderClient.queryByRequestId(record.getRequestId());
        } catch (RuntimeException e) {
            // order-service 不可用，保持 PRE_DEDUCTED，下一轮继续处理
            log.warn(
                    "查询订单失败，等待下一轮补偿, requestId={}",
                    record.getRequestId()
            );
            return;
        }
        if (response == null
                || !Objects.equals(response.getCode(), SUCCESS_CODE)
                || response.getData() == null) {
            return;
        }
        OrderQueryDto order = response.getData();
        if (Boolean.TRUE.equals(order.getExists())) {
            confirmRecord(record, order);
        } else {
            releaseRecord(record);
        }
    }

    private void confirmRecord(StockDeductRecordEntity record, OrderQueryDto order) {
        stockDeductService.confirm(record.getDeductNo(), order.getOrderNo()
        );
        log.info(
                "补偿确认成功, requestId={}, orderNo={}",
                record.getRequestId(),
                order.getOrderNo()
        );
    }

    private void releaseRecord(StockDeductRecordEntity record) {
        ResourceOrderCreateDto dto = buildCreateDto(record);
        // 先通过事务恢复 MySQL 库存
        stockDeductService.release(dto, record.getDeductNo(), "预扣超时且订单不存在");
        // release 成功后再恢复 Redis
        String stockKey = "floworder:stock:" + record.getStockItemId();
        try {
            stringRedisTemplate.opsForValue().increment(stockKey, record.getQuantity());
        } catch (RuntimeException incrementException) {
            try {
                // Redis 数值无法确定时删除，让后续请求从 MySQL 重建
                stringRedisTemplate.delete(stockKey);
            } catch (RuntimeException deleteException) {
                incrementException.addSuppressed(deleteException);
            }
            throw incrementException;
        }

        log.info(
                "补偿释放成功, requestId={}, deductNo={}",
                record.getRequestId(),
                record.getDeductNo()
        );
    }

    private ResourceOrderCreateDto buildCreateDto(StockDeductRecordEntity record) {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setRequestId(record.getRequestId());
        dto.setUserId(record.getUserId());
        dto.setResourceId(record.getResourceId());
        dto.setStockItemId(record.getStockItemId());
        dto.setQuantity(record.getQuantity());
        return dto;
    }
}
