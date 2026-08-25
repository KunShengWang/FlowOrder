package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.OrderQueryDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.StockLuaResultCodeEnum;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.redis.StockDeductLuaExecutor;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class StockDeductCompensationService {

    private static final int PRE_DEDUCTED = 10;
    private static final int SUCCESS_CODE = 200;
    private static final int BATCH_SIZE = 100;
    private static final int MANUAL_REVIEW = 50;
    private static final int MAX_QUERY_ERROR_COUNT = 5;
    private static final int CREATE_MODE_SYNC = 2;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private StockDeductService stockDeductService;

    @Resource
    private OrderClient orderClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private StockDeductLuaExecutor stockDeductLuaExecutor;

    public void compensateExpiredRecords() {
        List<StockDeductRecordEntity> records =
                deductRecordMapper.selectList(
                        Wrappers.<StockDeductRecordEntity>lambdaQuery()
                                .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED)// 已预扣
                                .eq(StockDeductRecordEntity::getCreateMode, CREATE_MODE_SYNC)
                                .le(StockDeductRecordEntity::getNextRetryTime, LocalDateTime.now())// 小于等于当前时间
                                .orderByAsc(StockDeductRecordEntity::getNextRetryTime)
                                .last("LIMIT " + BATCH_SIZE)
                );
        for (StockDeductRecordEntity record : records) {
            // 只有更新成功的实例获得处理权
            if (!claimRecord(record)) {
                continue;
            }
            try {
                compensateOne(record);
            } catch (RuntimeException e) {
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
            scheduleQueryRetry(record, "查询订单服务异常");
            return;
        }
        if (response == null) {
            scheduleQueryRetry(record, "订单查询响应为空");
            return;
        }
        if (!Objects.equals(response.getCode(), SUCCESS_CODE)) {
            scheduleQueryRetry(
                    record,
                    "订单查询失败, code=" + response.getCode()
            );
            return;
        }
        if (response.getData() == null) {
            scheduleQueryRetry(record, "订单查询data为空");
            return;
        }
        OrderQueryDto order = response.getData();
        if (Boolean.TRUE.equals(order.getExists())) {
            if (!StringUtils.hasText(order.getOrderNo())) {
                scheduleQueryRetry(record, "订单存在但订单号为空");
                return;
            }
            confirmRecord(record, order);
            return;
        }
        if (Boolean.FALSE.equals(order.getExists())) {
            handleOrderNotFound(record);
            return;
        }
        // exists=null 是协议异常，不能释放
        scheduleQueryRetry(record, "订单查询结果exists为空");
    }

    /**
     * 处理查询不到订单
     */
    private void handleOrderNotFound(StockDeductRecordEntity record) {
        int retryCount = Objects.requireNonNullElse(record.getRetryCount(), 0);

        if (retryCount >= 2) {
            releaseRecord(record);
            return;
        }

        long nextDelaySeconds = retryCount == 0 ? 10 : 30;

        int rows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED)// 已预扣
                        .eq(StockDeductRecordEntity::getRetryCount, retryCount)
                        .set(StockDeductRecordEntity::getRetryCount, retryCount + 1)
                        .set(StockDeductRecordEntity::getNextRetryTime, LocalDateTime.now().plusSeconds(nextDelaySeconds))
                        .set(StockDeductRecordEntity::getLastError, "订单暂未查询到").set(StockDeductRecordEntity::getQueryErrorCount, 0)
        );

        if (rows == 1) {
            log.info(
                    "订单暂不存在，安排下次确认, requestId={}, retryCount={}, delay={}s",
                    record.getRequestId(),
                    retryCount + 1,
                    nextDelaySeconds
            );
        }
    }

    /**
     * 查询异常只推迟，不释放
     */
    private void scheduleQueryRetry(StockDeductRecordEntity record, String error) {

        int currentCount = Objects.requireNonNullElse(record.getQueryErrorCount(), 0);
        int nextCount = currentCount + 1;

        var updateWrapper = Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED)// 已预扣
                        .eq(StockDeductRecordEntity::getQueryErrorCount, currentCount)
                        .set(StockDeductRecordEntity::getQueryErrorCount, nextCount)
                        .set(StockDeductRecordEntity::getLastError, error);

        if (nextCount >= MAX_QUERY_ERROR_COUNT) {
            // 查询结果始终未知，只停止自动处理，不释放库存
            updateWrapper
                    .set(StockDeductRecordEntity::getStatus, MANUAL_REVIEW)// 人工确认
                    .set(StockDeductRecordEntity::getNextRetryTime, null);
        } else {
            long delaySeconds = switch (nextCount) {
                case 1 -> 30;
                case 2 -> 60;
                case 3 -> 120;
                default -> 300;
            };

            updateWrapper.set(StockDeductRecordEntity::getNextRetryTime, LocalDateTime.now().plusSeconds(delaySeconds));
        }

        int rows = deductRecordMapper.update(null, updateWrapper);

        if (rows == 1 && nextCount >= MAX_QUERY_ERROR_COUNT) {
            log.error(
                    "订单查询连续失败，转人工确认, requestId={}, errorCount={}, error={}",
                    record.getRequestId(),
                    nextCount,
                    error
            );
        }
    }

    private void confirmRecord(StockDeductRecordEntity record, OrderQueryDto order) {
        stockDeductService.confirm(record.getDeductNo(), order.getOrderNo());
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
        compensateRedisSafely(stockKey, record.getQuantity());
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

    /**
     * redis库存恢复
     */
    private void compensateRedisSafely(String stockKey, Integer quantity) {
        try {
            Long result = stockDeductLuaExecutor.increment(stockKey, quantity);
            if (result == null) {
                throw new BizException("Redis库存恢复结果为空");
            }
            StockLuaResultCodeEnum resultCode = StockLuaResultCodeEnum.of(result);
            if (resultCode == null) {
                // 返回非负库存，恢复成功
                return;
            }
            if (resultCode == StockLuaResultCodeEnum.STOCK_KEY_MISSING) {
                /*
                 * key 已经不存在，不要使用 INCRBY 创建一个错误库存。
                 * 保持缓存缺失，下次请求从 MySQL 重新初始化。
                 */
                log.warn("恢复Redis库存时key不存在, stockKey={}", stockKey);
                return;
            }
            throw new BizException(resultCode);
        } catch (RuntimeException compensateException) {
            try{
                stringRedisTemplate.delete(stockKey);
            }catch (RuntimeException deleteException){
                compensateException.addSuppressed(deleteException);
            }
            log.error(
                    "Redis库存补偿失败, stockKey={}, quantity={}",
                    stockKey,
                    quantity,
                    compensateException
            );
            throw compensateException;
        }
    }

    /**
     * 更新预扣记录的下一次重试时间
     */
    private boolean claimRecord(StockDeductRecordEntity record) {
        LocalDateTime now = LocalDateTime.now();
        int rows = deductRecordMapper.update(
                null,
                Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                        .eq(StockDeductRecordEntity::getId, record.getId())
                        .eq(StockDeductRecordEntity::getStatus, PRE_DEDUCTED)// 已预扣
                        .eq(StockDeductRecordEntity::getCreateMode, CREATE_MODE_SYNC)
                        .le(StockDeductRecordEntity::getNextRetryTime, now)
                        // 60秒内不允许其他实例再次处理
                        .set(StockDeductRecordEntity::getNextRetryTime, now.plusSeconds(60))
        );
        return rows == 1;
    }
}
