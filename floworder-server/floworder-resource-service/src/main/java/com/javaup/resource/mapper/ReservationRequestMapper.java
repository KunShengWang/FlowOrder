package com.javaup.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaup.resource.entity.ReservationRequestEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRequestMapper
        extends BaseMapper<ReservationRequestEntity> {

    @Select("""
            SELECT *
            FROM fo_reservation_request
            WHERE status IN (0, 30)
              AND (next_retry_time IS NULL
                   OR next_retry_time <= #{now})
              AND (claim_until IS NULL
                   OR claim_until < #{now})
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<ReservationRequestEntity> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE fo_reservation_request
            SET status = 10,
                claim_owner = #{owner},
                claim_until = #{claimUntil},
                started_at = COALESCE(started_at, #{now}),
                version = version + 1
            WHERE id = #{id}
              AND status IN (0, 30)
              AND (next_retry_time IS NULL
                   OR next_retry_time <= #{now})
              AND (claim_until IS NULL
                   OR claim_until < #{now})
            """)
    int claim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") LocalDateTime now,
            @Param("claimUntil") LocalDateTime claimUntil
    );

    @Update("""
            UPDATE fo_reservation_request
            SET status = 20,
                order_no = #{orderNo},
                order_status = 10,
                claim_owner = NULL,
                claim_until = NULL,
                last_error = NULL,
                finished_at = #{now},
                version = version + 1
            WHERE id = #{id}
              AND status = 10
              AND claim_owner = #{owner}
            """)
    int markSucceeded(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("orderNo") String orderNo,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE fo_reservation_request
            SET order_status = #{toStatus},
                latest_order_event_type = #{eventType},
                latest_order_event_time = COALESCE(#{occurredAt}, #{now}),
                order_event_version = order_event_version + 1,
                finished_at = CASE
                    WHEN finished_at IS NULL THEN #{now}
                    ELSE finished_at
                END,
                version = version + 1
            WHERE request_id = #{requestId}
              AND order_no = #{orderNo}
              AND status = 20
              AND (order_status IS NULL OR order_status = #{fromStatus})
            """)
    int markOrderStateChanged(
            @Param("requestId") String requestId,
            @Param("orderNo") String orderNo,
            @Param("fromStatus") Integer fromStatus,
            @Param("toStatus") Integer toStatus,
            @Param("eventType") String eventType,
            @Param("occurredAt") LocalDateTime occurredAt,
            @Param("now") LocalDateTime now
    );

    /**
     * 处理中的状态改为待重试
     */
    @Update("""
            UPDATE fo_reservation_request
            SET status = 30,
                retry_count = retry_count + 1,
                next_retry_time = #{nextRetryTime},
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{error},
                version = version + 1
            WHERE id = #{id}
              AND status = 10
              AND claim_owner = #{owner}
            """)
    int markRetry(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("error") String error
    );

    @Update("""
            UPDATE fo_reservation_request
            SET status = 40,
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{error},
                finished_at = #{now},
                version = version + 1
            WHERE id = #{id}
              AND status = 10
              AND claim_owner = #{owner}
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );

    /**
     * 处理中的状态改为人工审核
     */
    @Update("""
            UPDATE fo_reservation_request
            SET status = 50,
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{error},
                finished_at = #{now},
                version = version + 1
            WHERE id = #{id}
              AND status = 10
              AND claim_owner = #{owner}
            """)
    int markManualReview(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("error") String error,
            @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE fo_reservation_request
        SET status = 30,
            next_retry_time = #{nextRetryTime},
            claim_owner = NULL,
            claim_until = NULL,
            last_error = #{error},
            version = version + 1
        WHERE id = #{id}
          AND status = 10
          AND claim_owner = #{owner}
        """)
    int releaseClaim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("error") String error
    );

    /**
     * 状态从处理中改为待重试
     */
    @Update("""
        UPDATE fo_reservation_request
        SET status = CASE
                WHEN retry_count + 1 >= #{maxRetry}
                    THEN 50
                ELSE 30
            END,
            next_retry_time = CASE
                WHEN retry_count + 1 >= #{maxRetry}
                    THEN NULL
                ELSE #{now}
            END,
            finished_at = CASE
                WHEN retry_count + 1 >= #{maxRetry}
                    THEN #{now}
                ELSE finished_at
            END,
            claim_owner = NULL,
            claim_until = NULL,
            last_error = CASE
                WHEN retry_count + 1 >= #{maxRetry}
                    THEN 'lease expired and retry limit reached'
                ELSE 'processing lease expired'
            END,
            retry_count = retry_count + 1,
            version = version + 1
        WHERE status = 10
          AND claim_until IS NOT NULL
          AND claim_until < #{now}
        LIMIT #{limit}
        """)
    int recoverExpired(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("maxRetry") int maxRetry
    );
}
