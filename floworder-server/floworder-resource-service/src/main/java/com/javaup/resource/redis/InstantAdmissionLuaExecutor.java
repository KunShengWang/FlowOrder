package com.javaup.resource.redis;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javaup.constant.RedisConstant.FLOWORDER_STOCK;
import static com.javaup.constant.RedisConstant.INSTANT_ADMISSION;
import static com.javaup.constant.RedisConstant.INSTANT_UNPERSISTED;

@Component
public class InstantAdmissionLuaExecutor {

    private static final DefaultRedisScript<Long> ADMISSION_SCRIPT = script("lua/instantAdmission.lua");
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("lua/instantAdmissionRelease.lua");
    private static final DefaultRedisScript<Long> MARK_PERSISTED_SCRIPT = script("lua/instantAdmissionMarkPersisted.lua");
    private static final DefaultRedisScript<Long> MARK_RELEASED_SCRIPT = script("lua/instantAdmissionMarkReleased.lua");

    private final StringRedisTemplate redisTemplate;
    private final long credentialTtlMillis;

    public InstantAdmissionLuaExecutor(
            StringRedisTemplate redisTemplate,
            @Value("${floworder.instant.credential-ttl-hours:24}") long credentialTtlHours
    ) {
        this.redisTemplate = redisTemplate;
        this.credentialTtlMillis = Math.max(1, credentialTtlHours) * 60L * 60L * 1000L;
    }

    public InstantAdmissionResultEnum admit(ResourceOrderCreateDto dto, String digest) {
        Long result = redisTemplate.execute(
                ADMISSION_SCRIPT,
                List.of(stockKey(dto.getStockItemId()), credentialKey(dto.getRequestId()), INSTANT_UNPERSISTED),
                dto.getRequestId(),
                digest,
                String.valueOf(dto.getStockItemId()),
                String.valueOf(dto.getQuantity()),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(credentialTtlMillis)
        );
        if (result == null) {
            throw new IllegalStateException("Instant Redis准入结果为空");
        }
        return InstantAdmissionResultEnum.of(result);
    }

    public void release(
            Long stockItemId,
            String requestId,
            String digest,
            Integer quantity,
            boolean invalidateStock
    ) {
        Long result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(stockKey(stockItemId), credentialKey(requestId), INSTANT_UNPERSISTED),
                requestId,
                digest,
                String.valueOf(quantity),
                invalidateStock ? "1" : "0",
                String.valueOf(credentialTtlMillis)
        );
        if (result == null || result < 0) {
            throw new IllegalStateException("Instant Redis准入释放失败，result=" + result);
        }
    }

    public void markPersisted(String requestId) {
        Long result = redisTemplate.execute(
                MARK_PERSISTED_SCRIPT,
                List.of(credentialKey(requestId), INSTANT_UNPERSISTED),
                requestId,
                String.valueOf(credentialTtlMillis)
        );
        if (result == null) {
            throw new IllegalStateException("Instant Redis准入落库标记失败");
        }
    }

    public void markReleasedAfterCacheInvalidation(String requestId) {
        Long result = redisTemplate.execute(
                MARK_RELEASED_SCRIPT,
                List.of(credentialKey(requestId), INSTANT_UNPERSISTED),
                requestId,
                String.valueOf(credentialTtlMillis)
        );
        if (result == null) {
            throw new IllegalStateException("Instant Redis准入凭证更新失败");
        }
    }

    public Set<String> findExpiredUnpersisted(long deadlineMillis, int limit) {
        return redisTemplate.opsForZSet().rangeByScore(
                INSTANT_UNPERSISTED,
                0,
                deadlineMillis,
                0,
                Math.max(1, limit)
        );
    }

    public Map<Object, Object> credential(String requestId) {
        return redisTemplate.opsForHash().entries(credentialKey(requestId));
    }

    public boolean isHeld(String requestId, String digest) {
        Map<Object, Object> credential = credential(requestId);
        if (credential.isEmpty()) {
            return false;
        }
        Object storedDigest = credential.get("digest");
        if (storedDigest != null && !digest.equals(storedDigest.toString())) {
            throw new IllegalStateException("Instant Redis准入凭证参数冲突");
        }
        return "1".equals(String.valueOf(credential.get("deducted")));
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private String stockKey(Long stockItemId) {
        return FLOWORDER_STOCK + stockItemId;
    }

    private String credentialKey(String requestId) {
        return INSTANT_ADMISSION + requestId;
    }
}
