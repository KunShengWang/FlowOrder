package com.javaup.resource;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.entity.ReservationRequestEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import com.javaup.resource.mapper.ReservationRequestMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.redis.InstantAdmissionLuaExecutor;
import com.javaup.resource.service.InstantAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstantAdmissionServiceTest {

    @Mock
    private InstantAdmissionLuaExecutor executor;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private StockItemMapper stockItemMapper;
    @Mock
    private ReservationRequestMapper requestMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private InstantAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new InstantAdmissionService(
                executor,
                redisTemplate,
                stockItemMapper,
                requestMapper
        );
    }

    @Test
    void cacheMissingShouldInitializeFromMysqlAndRetryLuaOnce() {
        ResourceOrderCreateDto dto = dto();
        StockItemEntity stock = new StockItemEntity();
        stock.setId(20L);
        stock.setAvailableStock(100);
        stock.setStatus(1);
        stock.setDeleted(0);
        when(executor.admit(eq(dto), anyString()))
                .thenReturn(InstantAdmissionResultEnum.CACHE_MISSING)
                .thenReturn(InstantAdmissionResultEnum.ADMITTED_NEW);
        when(stockItemMapper.selectById(20L)).thenReturn(stock);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        InstantAdmissionService.AdmissionAttempt attempt = service.admit(dto);

        assertEquals(InstantAdmissionResultEnum.ADMITTED_NEW, attempt.result());
        verify(valueOperations).setIfAbsent("floworder:stock:20", "100");
        verify(executor, times(2)).admit(eq(dto), eq(attempt.digest()));
    }

    @Test
    void orphanWithoutMysqlRowShouldReleaseExactlyOnce() {
        when(executor.findExpiredUnpersisted(1000L, 10)).thenReturn(Set.of("request-1"));
        when(requestMapper.selectOne(any())).thenReturn(null);
        when(executor.credential("request-1")).thenReturn(Map.of(
                "stockItemId", "20",
                "quantity", "2",
                "digest", "digest",
                "deducted", "1"
        ));

        int recovered = service.recoverExpiredUnpersisted(1000L, 10);

        assertEquals(1, recovered);
        verify(executor, times(1)).release(20L, "request-1", "digest", 2, false);
    }

    @Test
    void orphanIndexWithMysqlRowShouldOnlyRemoveZsetMember() {
        when(executor.findExpiredUnpersisted(1000L, 10)).thenReturn(Set.of("request-1"));
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setUserId(1001L);
        request.setResourceId(10L);
        request.setStockItemId(20L);
        request.setQuantity(1);
        when(requestMapper.selectOne(any())).thenReturn(request);
        when(executor.credential("request-1")).thenReturn(Map.of(
                "stockItemId", "20",
                "quantity", "1",
                "digest", service.digest(dto()),
                "deducted", "1"
        ));

        assertEquals(0, service.recoverExpiredUnpersisted(1000L, 10));

        verify(executor).markPersisted("request-1");
        verify(executor, never()).release(anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void orphanCredentialConflictingWithMysqlRequestShouldReleaseExtraDeduction() {
        when(executor.findExpiredUnpersisted(1000L, 10)).thenReturn(Set.of("request-1"));
        ReservationRequestEntity request = new ReservationRequestEntity();
        request.setUserId(9999L);
        request.setResourceId(10L);
        request.setStockItemId(20L);
        request.setQuantity(1);
        when(requestMapper.selectOne(any())).thenReturn(request);
        when(executor.credential("request-1")).thenReturn(Map.of(
                "stockItemId", "20",
                "quantity", "1",
                "digest", "new-conflicting-digest",
                "deducted", "1"
        ));

        assertEquals(1, service.recoverExpiredUnpersisted(1000L, 10));

        verify(executor).release(
                20L,
                "request-1",
                "new-conflicting-digest",
                1,
                false
        );
    }

    @Test
    void digestMustIncludeEveryBusinessIdempotencyField() {
        ResourceOrderCreateDto original = dto();
        String digest = service.digest(original);

        ResourceOrderCreateDto changedUser = dto();
        changedUser.setUserId(1002L);
        ResourceOrderCreateDto changedResource = dto();
        changedResource.setResourceId(11L);
        ResourceOrderCreateDto changedStock = dto();
        changedStock.setStockItemId(21L);
        ResourceOrderCreateDto changedQuantity = dto();
        changedQuantity.setQuantity(2);

        assertEquals(digest, service.digest(dto()));
        assertNotEquals(digest, service.digest(changedUser));
        assertNotEquals(digest, service.digest(changedResource));
        assertNotEquals(digest, service.digest(changedStock));
        assertNotEquals(digest, service.digest(changedQuantity));
    }

    private ResourceOrderCreateDto dto() {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setRequestId("request-1");
        dto.setUserId(1001L);
        dto.setResourceId(10L);
        dto.setStockItemId(20L);
        dto.setQuantity(1);
        return dto;
    }
}
