package com.javaup.resource;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.mapper.UserReservationQuotaMapper;
import com.javaup.resource.service.impl.ReservationAdmissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationAdmissionServiceTest {

    @Mock
    private StockItemMapper stockItemMapper;

    @Mock
    private UserReservationQuotaMapper quotaMapper;

    private ReservationAdmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReservationAdmissionServiceImpl(
                stockItemMapper,
                quotaMapper
        );
    }

    @Test
    void checkShouldPassWhenWindowQualificationAndQuotaAreValid() {
        mockValidAdmission();

        assertDoesNotThrow(() -> service.check(request(1)));
    }

    @Test
    void checkShouldRejectBeforeReservationStarts() {
        StockItemEntity stock = validStockItem();
        stock.setStartTime(LocalDateTime.now().plusMinutes(10));

        when(stockItemMapper.selectById(1L)).thenReturn(stock);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("预约尚未开始", exception.getMessage());
        verifyNoInteractions(quotaMapper);
    }

    @Test
    void checkShouldRejectAfterReservationEnds() {
        StockItemEntity stock = validStockItem();
        stock.setEndTime(LocalDateTime.now().minusMinutes(10));

        when(stockItemMapper.selectById(1L)).thenReturn(stock);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("预约已经结束", exception.getMessage());
        verifyNoInteractions(quotaMapper);
    }

    @Test
    void checkShouldRejectWhenQualificationDoesNotExist() {
        when(stockItemMapper.selectById(1L))
                .thenReturn(validStockItem());

        when(quotaMapper.selectOne(any())).thenReturn(null);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("用户不具备预约资格", exception.getMessage());
    }

    @Test
    void checkShouldRejectWhenQualificationIsDisabled() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setStatus(0);
        mockAdmissionWithQuota(quota);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("用户预约资格无效", exception.getMessage());
    }

    @Test
    void checkShouldRejectBeforeQualificationBecomesValid() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setValidFrom(LocalDateTime.now().plusMinutes(10));
        mockAdmissionWithQuota(quota);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("用户预约资格尚未生效", exception.getMessage());
    }

    @Test
    void checkShouldRejectAfterQualificationExpires() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setValidUntil(LocalDateTime.now().minusMinutes(10));
        mockAdmissionWithQuota(quota);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("用户预约资格已失效", exception.getMessage());
    }

    @Test
    void checkShouldRejectWhenQualificationBelongsToAnotherResource() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setResourceId(2L);
        mockAdmissionWithQuota(quota);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(1))
        );

        assertEquals("用户预约资格与资源不匹配", exception.getMessage());
    }

    @Test
    void checkShouldRejectWhenQuotaIsInsufficient() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setLimitQuantity(5);
        quota.setUsedQuantity(4);

        when(stockItemMapper.selectById(1L))
                .thenReturn(validStockItem());

        when(quotaMapper.selectOne(any()))
                .thenReturn(quota);

        BizException exception = assertThrows(
                BizException.class,
                () -> service.check(request(2))
        );

        assertEquals("用户预约额度不足", exception.getMessage());
    }

    @Test
    void checkShouldFailWhenUsedQuantityExceedsLimit() {
        UserReservationQuotaEntity quota = validQuota();
        quota.setLimitQuantity(5);
        quota.setUsedQuantity(6);

        when(stockItemMapper.selectById(1L))
                .thenReturn(validStockItem());

        when(quotaMapper.selectOne(any()))
                .thenReturn(quota);

        assertThrows(
                IllegalStateException.class,
                () -> service.check(request(1))
        );
    }

    @Test
    void reserveQuotaShouldRejectWhenConditionalUpdateFails() {
        ResourceOrderCreateDto dto = request(1);

        when(quotaMapper.reserveQuota(
                eq(1L),
                eq(1L),
                eq(1001L),
                eq(1),
                any(LocalDateTime.class)
        )).thenReturn(0);

        assertThrows(
                BizException.class,
                () -> service.reserveQuota(dto, LocalDateTime.now())
        );
    }

    @Test
    void reserveQuotaShouldPassWhenConditionalUpdateSucceeds() {
        ResourceOrderCreateDto dto = request(2);
        LocalDateTime now = LocalDateTime.now();

        when(quotaMapper.reserveQuota(
                1L, 1L, 1001L, 2, now
        )).thenReturn(1);

        assertDoesNotThrow(() -> service.reserveQuota(dto, now));
        verify(quotaMapper).reserveQuota(1L, 1L, 1001L, 2, now);
    }

    @Test
    void releaseQuotaShouldFailWhenConditionalUpdateFails() {
        StockDeductRecordEntity record = deductRecord(1);

        when(quotaMapper.releaseQuota(
                1L, 1L, 1001L, 1
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.releaseQuota(record)
        );
    }

    @Test
    void releaseQuotaShouldPassWhenConditionalUpdateSucceeds() {
        StockDeductRecordEntity record = deductRecord(2);
        when(quotaMapper.releaseQuota(1L, 1L, 1001L, 2))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.releaseQuota(record));
        verify(quotaMapper).releaseQuota(1L, 1L, 1001L, 2);
    }

    private ResourceOrderCreateDto request(int quantity) {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setResourceId(1L);
        dto.setStockItemId(1L);
        dto.setUserId(1001L);
        dto.setQuantity(quantity);
        dto.setRequestId("req-v7-test");
        return dto;
    }

    private StockItemEntity validStockItem() {
        StockItemEntity stock = new StockItemEntity();
        stock.setId(1L);
        stock.setResourceId(1L);
        stock.setStatus(1);
        stock.setDeleted(0);
        stock.setStartTime(LocalDateTime.now().minusHours(1));
        stock.setEndTime(LocalDateTime.now().plusHours(1));
        return stock;
    }

    private UserReservationQuotaEntity validQuota() {
        UserReservationQuotaEntity quota =
                new UserReservationQuotaEntity();

        quota.setResourceId(1L);
        quota.setStockItemId(1L);
        quota.setUserId(1001L);
        quota.setStatus(1);
        quota.setLimitQuantity(5);
        quota.setUsedQuantity(1);
        quota.setValidFrom(LocalDateTime.now().minusHours(1));
        quota.setValidUntil(LocalDateTime.now().plusHours(1));
        return quota;
    }

    private void mockValidAdmission() {
        when(stockItemMapper.selectById(1L))
                .thenReturn(validStockItem());

        when(quotaMapper.selectOne(any()))
                .thenReturn(validQuota());
    }

    private void mockAdmissionWithQuota(UserReservationQuotaEntity quota) {
        when(stockItemMapper.selectById(1L))
                .thenReturn(validStockItem());
        when(quotaMapper.selectOne(any())).thenReturn(quota);
    }

    private StockDeductRecordEntity deductRecord(int quantity) {
        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setResourceId(1L);
        record.setStockItemId(1L);
        record.setUserId(1001L);
        record.setQuantity(quantity);
        return record;
    }
}
