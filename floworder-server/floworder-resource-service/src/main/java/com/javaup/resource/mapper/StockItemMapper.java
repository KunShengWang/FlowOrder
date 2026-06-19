package com.javaup.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaup.resource.entity.StockItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StockItemMapper extends BaseMapper<StockItemEntity> {

    /**
     * V3/V7最终库存预扣。
     *
     * 同时检查：
     * 1. 资源可用；
     * 2. 库存项可用；
     * 3. 预约窗口有效；
     * 4. MySQL库存充足。
     */
    @Update("""
            UPDATE fo_stock_item s
            INNER JOIN fo_resource r
                    ON r.id = s.resource_id
            SET s.available_stock = s.available_stock - #{quantity},
                s.locked_stock = s.locked_stock + #{quantity},
                s.version = s.version + 1
            WHERE s.id = #{stockItemId}
              AND s.resource_id = #{resourceId}
              AND s.status = 1
              AND s.deleted = 0
              AND r.status = 1
              AND r.deleted = 0
              AND (s.start_time IS NULL OR s.start_time <= #{now})
              AND (s.end_time IS NULL OR s.end_time > #{now})
              AND s.available_stock >= #{quantity}
            """)
    int preDeductIfAdmissible(
            @Param("resourceId") Long resourceId,
            @Param("stockItemId") Long stockItemId,
            @Param("quantity") Integer quantity,
            @Param("now") LocalDateTime now
    );
}
