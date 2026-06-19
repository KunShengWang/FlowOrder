package com.javaup.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface UserReservationQuotaMapper extends BaseMapper<UserReservationQuotaEntity> {

    /**
     * 原子占用用户预约额度。
     *
     * used_quantity <= limit_quantity - quantity
     * 比 used_quantity + quantity <= limit_quantity 更容易避免整数加法溢出。
     */
    @Update("""
            UPDATE fo_user_reservation_quota
            SET used_quantity = used_quantity + #{quantity},
                version = version + 1
            WHERE resource_id = #{resourceId}
              AND stock_item_id = #{stockItemId}
              AND user_id = #{userId}
              AND status = 1
              AND (valid_from IS NULL OR valid_from <= #{now})
              AND (valid_until IS NULL OR valid_until > #{now})
              AND used_quantity <= limit_quantity - #{quantity}
            """)
    int reserveQuota(
            @Param("resourceId") Long resourceId,
            @Param("stockItemId") Long stockItemId,
            @Param("userId") Long userId,
            @Param("quantity") Integer quantity,
            @Param("now") LocalDateTime now
    );

    /**
     * 归还额度时不检查资格状态和有效期。
     *
     * 即使资格已失效或管理员禁用了资格，之前占用的额度仍然必须能够归还。
     */
    @Update("""
            UPDATE fo_user_reservation_quota
            SET used_quantity = used_quantity - #{quantity},
                version = version + 1
            WHERE resource_id = #{resourceId}
              AND stock_item_id = #{stockItemId}
              AND user_id = #{userId}
              AND used_quantity >= #{quantity}
            """)
    int releaseQuota(
            @Param("resourceId") Long resourceId,
            @Param("stockItemId") Long stockItemId,
            @Param("userId") Long userId,
            @Param("quantity") Integer quantity
    );
}