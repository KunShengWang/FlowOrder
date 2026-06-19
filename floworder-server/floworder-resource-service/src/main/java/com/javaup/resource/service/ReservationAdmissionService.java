package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.entity.StockDeductRecordEntity;

import java.time.LocalDateTime;

public interface ReservationAdmissionService {

    /**
     * 前置快速校验，不承担最终并发正确性。
     */
    void check(ResourceOrderCreateDto dto);

    /**
     * MySQL事务中的最终额度占用。
     */
    void reserveQuota(ResourceOrderCreateDto dto, LocalDateTime now);

    /**
     * 明确失败、取消或超时时归还额度。
     */
    void releaseQuota(StockDeductRecordEntity record);
}