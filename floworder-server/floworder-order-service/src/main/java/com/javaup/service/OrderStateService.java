package com.javaup.service;

import java.util.List;

public interface OrderStateService {

    /**
     * 查找过期订单的id
     */
    List<Long> findExpiredOrderIds(int batchSize);

    boolean timeout(Long orderId);
}
