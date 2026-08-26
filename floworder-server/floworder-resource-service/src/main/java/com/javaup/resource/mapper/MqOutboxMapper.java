package com.javaup.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaup.resource.entity.MqOutboxEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MqOutboxMapper extends BaseMapper<MqOutboxEntity> {

    @Update("""
            UPDATE fo_mq_outbox
            SET status = 10,
                claim_owner = #{claimOwner},
                claim_token = #{claimToken},
                claim_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)),
                updated_at = NOW(3)
            WHERE id = #{id}
              AND producer_service = #{producerService}
              AND status IN (0, 30)
              AND next_retry_time <= NOW(3)
            """)
    int claim(
            @Param("id") Long id,
            @Param("producerService") String producerService,
            @Param("claimOwner") String claimOwner,
            @Param("claimToken") String claimToken,
            @Param("leaseSeconds") long leaseSeconds
    );

    @Update("""
            UPDATE fo_mq_outbox
            SET status = 30,
                next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND),
                claim_owner = NULL,
                claim_token = NULL,
                claim_until = NULL,
                last_error = '发送租约过期，等待重新发送',
                updated_at = NOW(3)
            WHERE id = #{id}
              AND producer_service = #{producerService}
              AND status = 10
              AND claim_token = #{claimToken}
              AND claim_until <= NOW(3)
            """)
    int reclaimExpired(
            @Param("id") Long id,
            @Param("producerService") String producerService,
            @Param("claimToken") String claimToken
    );
}
