package com.javaup.resource.service;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;

public interface StockDeductService {

    /**
     * 库存预扣
     * 1. 插入预扣记录
     * 2. available_stock -> locked_stock
     */
    void preDeduct(ResourceOrderCreateDto dto, StockDeductRecordEntity record);

    void confirm(String deductNo, String orderNo);

    void release(ResourceOrderCreateDto dto, String deductNo, String reason);

    void preDeductAndSaveOutbox(ResourceOrderCreateDto createDto, StockDeductRecordEntity record, MqOutboxEntity outbox);
}
