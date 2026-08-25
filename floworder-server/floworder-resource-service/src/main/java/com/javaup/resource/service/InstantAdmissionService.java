package com.javaup.resource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.BaseCodeEnum;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.redis.InstantAdmissionLuaExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.javaup.constant.RedisConstant.FLOWORDER_STOCK;

@Slf4j
@Service
public class InstantAdmissionService {

    private final InstantAdmissionLuaExecutor executor;
    private final StringRedisTemplate redisTemplate;
    private final StockItemMapper stockItemMapper;
    private final ReservationRequestMapper requestMapper;

    public InstantAdmissionService(
            InstantAdmissionLuaExecutor executor,
            StringRedisTemplate redisTemplate,
            StockItemMapper stockItemMapper,
            ReservationRequestMapper requestMapper
    ) {
        this.executor = executor;
        this.redisTemplate = redisTemplate;
        this.stockItemMapper = stockItemMapper;
        this.requestMapper = requestMapper;
    }

    public AdmissionAttempt admit(ResourceOrderCreateDto dto) {
        String digest = digest(dto);
        try {
            // 执行 Redis Lua 脚本
            InstantAdmissionResultEnum result = executor.admit(dto, digest);
            if (result == InstantAdmissionResultEnum.CACHE_MISSING) {
                initStockCache(dto.getStockItemId());
                result = executor.admit(dto, digest);
            }
            return new AdmissionAttempt(result, digest);
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Instant Redis准入失败, requestId={}", dto.getRequestId(), exception);
            throw new BizException("Redis准入不可用，请稍后重试");
        }
    }

    public void release(ResourceOrderCreateDto dto, String digest, boolean invalidateStock) {
        executor.release(
                dto.getStockItemId(),
                dto.getRequestId(),
                digest,
                dto.getQuantity(),
                invalidateStock
        );
    }

    public void markPersistedBestEffort(String requestId) {
        try {
            executor.markPersisted(requestId);
        } catch (RuntimeException exception) {
            log.warn("Instant请求已落库但Redis未落库索引清理失败, requestId={}", requestId, exception);
        }
    }

    public boolean isHeld(ResourceOrderCreateDto dto, String digest) {
        return executor.isHeld(dto.getRequestId(), digest);
    }

    public void markReleasedAfterCacheInvalidation(String requestId) {
        executor.markReleasedAfterCacheInvalidation(requestId);
    }

    public int recoverExpiredUnpersisted(long deadlineMillis, int limit) {
        Set<String> requestIds = executor.findExpiredUnpersisted(deadlineMillis, limit);
        if (requestIds == null || requestIds.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        for (String requestId : requestIds) {
            ReservationRequestEntity request = requestMapper.selectOne(
                    Wrappers.<ReservationRequestEntity>lambdaQuery()
                            .eq(ReservationRequestEntity::getRequestId, requestId)
                            .last("limit 1")
            );
            Map<Object, Object> credential = executor.credential(requestId);
            if (credential.isEmpty()) {
                executor.markPersisted(requestId);
                continue;
            }
            try {
                String storedDigest = String.valueOf(credential.get("digest"));
                if (request != null && Objects.equals(storedDigest, digest(request))) {
                    executor.markPersisted(requestId);
                    continue;
                }
                Long stockItemId = Long.valueOf(String.valueOf(credential.get("stockItemId")));
                Integer quantity = Integer.valueOf(String.valueOf(credential.get("quantity")));
                executor.release(stockItemId, requestId, storedDigest, quantity, false);
                recovered++;
                log.warn("恢复Instant未落库Redis准入, requestId={}, stockItemId={}", requestId, stockItemId);
            } catch (RuntimeException exception) {
                log.error("恢复Instant未落库Redis准入失败, requestId={}", requestId, exception);
            }
        }
        return recovered;
    }

    public String digest(ResourceOrderCreateDto dto) {
        String canonical = dto.getUserId() + "|"
                + dto.getResourceId() + "|"
                + dto.getStockItemId() + "|"
                + dto.getQuantity();
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("生成Instant请求摘要失败", exception);
        }
    }

    private String digest(ReservationRequestEntity request) {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setUserId(request.getUserId());
        dto.setResourceId(request.getResourceId());
        dto.setStockItemId(request.getStockItemId());
        dto.setQuantity(request.getQuantity());
        return digest(dto);
    }

    private void initStockCache(Long stockItemId) {
        StockItemEntity stock = stockItemMapper.selectById(stockItemId);
        if (stock == null || Objects.equals(stock.getDeleted(), 1)) {
            throw new BizException(BaseCodeEnum.StockItem_NOT_EXIST);
        }
        if (!Objects.equals(stock.getStatus(), 1)) {
            throw new BizException(BaseCodeEnum.StockItem_NOT_OPEN);
        }
        redisTemplate.opsForValue().setIfAbsent(
                FLOWORDER_STOCK + stockItemId,
                String.valueOf(Objects.requireNonNullElse(stock.getAvailableStock(), 0))
        );
    }

    public record AdmissionAttempt(
            InstantAdmissionResultEnum result,
            String digest
    ) {
    }
}
