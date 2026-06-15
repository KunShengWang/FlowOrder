package com.javaup.service;

import com.javaup.dto.OrderStateDetailDto;

import java.util.List;

public interface OrderStateService {

    /**
     * 订单确认
     */
    void confirm(String orderNo, Long userId);

    /**
     * 订单取消
     */
    void cancel(String orderNo, Long userId, String reason);

    /**
     * 查找过期订单的id
     */
    List<Long> findExpiredOrderIds(int batchSize);

    boolean timeout(Long orderId);

    OrderStateDetailDto detail(String orderNo, Long userId);
}
