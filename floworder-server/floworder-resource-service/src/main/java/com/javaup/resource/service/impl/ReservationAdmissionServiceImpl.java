package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.service.ReservationAdmissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class ReservationAdmissionServiceImpl implements ReservationAdmissionService {

    private final StockItemMapper stockItemMapper;
    private final UserReservationQuotaMapper quotaMapper;

    public ReservationAdmissionServiceImpl(
            StockItemMapper stockItemMapper,
            UserReservationQuotaMapper quotaMapper
    ) {
        this.stockItemMapper = stockItemMapper;
        this.quotaMapper = quotaMapper;
    }

    @Override
    public void check(ResourceOrderCreateDto dto) {
        LocalDateTime now = LocalDateTime.now();
        StockItemEntity stockItem = stockItemMapper.selectById(dto.getStockItemId());
        validateStockItem(dto, stockItem, now);
        UserReservationQuotaEntity quota = quotaMapper.selectOne(
                Wrappers.<UserReservationQuotaEntity>lambdaQuery()
                        .eq(UserReservationQuotaEntity::getStockItemId, dto.getStockItemId())
                        .eq(UserReservationQuotaEntity::getUserId, dto.getUserId())
        );
        validateQuota(dto, quota, now);
    }

    @Override
    public void reserveQuota(ResourceOrderCreateDto dto, LocalDateTime now) {
        int rows = quotaMapper.reserveQuota(
                dto.getResourceId(),
                dto.getStockItemId(),
                dto.getUserId(),
                dto.getQuantity(),
                now
        );
        if (rows != 1) {
            throw new BizException("用户资格无效或预约额度不足");
        }
    }

    @Override
    public void releaseQuota(StockDeductRecordEntity record) {
        int rows = quotaMapper.releaseQuota(
                record.getResourceId(),
                record.getStockItemId(),
                record.getUserId(),
                record.getQuantity()
        );
        if (rows != 1) {
            /*
             * 额度归还失败属于一致性异常，不能作为普通业务失败吞掉。
             * 后续 MQ 消费事务应回滚并重试。
             */
            throw new IllegalStateException("用户预约额度归还失败");
        }
    }

    private void validateStockItem(
            ResourceOrderCreateDto dto,
            StockItemEntity stockItem,
            LocalDateTime now
    ) {
        if (stockItem == null
                || Objects.equals(stockItem.getDeleted(), 1)) {
            throw new BizException("库存项不存在");
        }

        if (!Objects.equals(
                stockItem.getResourceId(),
                dto.getResourceId())) {
            throw new BizException("库存项不属于当前资源");
        }

        if (!Objects.equals(stockItem.getStatus(), 1)) {
            throw new BizException("库存项未启用");
        }

        if (stockItem.getStartTime() != null
                && now.isBefore(stockItem.getStartTime())) {
            throw new BizException("预约尚未开始");
        }

        /*
         * 使用左闭右开区间：
         * now == endTime 时已经结束。
         */
        if (stockItem.getEndTime() != null
                && !now.isBefore(stockItem.getEndTime())) {
            throw new BizException("预约已经结束");
        }
    }

    private void validateQuota(
            ResourceOrderCreateDto dto,
            UserReservationQuotaEntity quota,
            LocalDateTime now
    ) {
        if (quota == null) {
            throw new BizException("用户不具备预约资格");
        }

        if (!Objects.equals(
                quota.getResourceId(),
                dto.getResourceId())) {
            throw new BizException("用户预约资格与资源不匹配");
        }

        if (!Objects.equals(quota.getStatus(), 1)) {
            throw new BizException("用户预约资格无效");
        }

        if (quota.getValidFrom() != null
                && now.isBefore(quota.getValidFrom())) {
            throw new BizException("用户预约资格尚未生效");
        }

        if (quota.getValidUntil() != null
                && !now.isBefore(quota.getValidUntil())) {
            throw new BizException("用户预约资格已失效");
        }

        if (quota.getLimitQuantity() == null
                || quota.getUsedQuantity() == null
                || quota.getLimitQuantity() < 0
                || quota.getUsedQuantity() < 0
                || quota.getUsedQuantity() > quota.getLimitQuantity()) {
            throw new IllegalStateException("用户预约额度数据异常");
        }

        long requestedTotal =
                (long) quota.getUsedQuantity() + dto.getQuantity();

        if (requestedTotal > quota.getLimitQuantity()) {
            throw new BizException("用户预约额度不足");
        }
    }
}