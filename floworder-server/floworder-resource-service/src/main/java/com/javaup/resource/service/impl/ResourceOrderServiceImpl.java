package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.service.ResourceOrderService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

@Service
public class ResourceOrderServiceImpl implements ResourceOrderService {

    private static final Integer STOCK_DEDUCT_STATUS_PRE_DEDUCTED = 10;

    private static final Integer STOCK_DEDUCT_STATUS_CONFIRMED = 20;

    private static final Integer STOCK_DEDUCT_STATUS_FAILED = 40;

    private static final Integer SUCCESS_CODE = 200;

    private static final Integer DEFAULT_EXPIRE_MINUTES = 15;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper stockDeductRecordMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderClient orderClient;

    /**
     * 并发场景下，相同的 requestId 同时请求，是数据库的唯一key做兜底和防御
     */
    @Override
    public String createV1(ResourceOrderCreateDto createDto) {
        // 根据 requestId 查看库存扣减记录
        StockDeductRecordEntity oldRecord = getDeductRecordByRequestId(createDto.getRequestId());
        if (Objects.nonNull(oldRecord)) {
            return handleOldDeductRecord(oldRecord);
        }
        // 构造编号
        String orderNo = generateOrderNo();
        String deductNo = generateDeductNo();
        // 过期时间为15分钟
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(DEFAULT_EXPIRE_MINUTES);
        // 构造 redis 的 key
        String stockKey = buildStockKey(createDto.getStockItemId());
        // 初始化 redis 库存
        initStockCacheIfAbsent(createDto.getStockItemId(), stockKey);
        // 扣减 redis 库存，返回剩余的库存
        Long remainStock = stringRedisTemplate.opsForValue().decrement(stockKey, createDto.getQuantity());
        if (Objects.isNull(remainStock)) {
            throw new BizException("Redis扣减库存失败");
        }
        if (remainStock < 0) {
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            throw new BizException("库存不足");
        }
        // 构建库存预扣记录，此时的预扣记录的状态为 10(已预扣)
        StockDeductRecordEntity deductRecord = buildPreDeductRecord(createDto, deductNo, expireTime);
        try {
            stockDeductRecordMapper.insert(deductRecord);
        } catch (DuplicateKeyException e) {
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            // 根据 requestId 获取预扣记录
            StockDeductRecordEntity existRecord = getDeductRecordByRequestId(createDto.getRequestId());
            if (Objects.nonNull(existRecord)) {
                return handleOldDeductRecord(existRecord);
            }
            throw e;
        }
        // 上面是创建预扣订单，下面是扣减 redis 的库存
        try {
            // 远程调用创建订单
            ApiResponse<String> createOrderResponse = orderClient.create(buildCreateOrderDto(createDto, orderNo,
                    deductNo, expireTime));
            if (Objects.isNull(createOrderResponse) || !Objects.equals(createOrderResponse.getCode(), SUCCESS_CODE)) {
                throw new BizException("调用订单服务创建订单失败");
            }
            String realOrderNo = StringUtils.hasText(createOrderResponse.getData()) ? createOrderResponse.getData() :
                    orderNo;
            // 更新库存预扣记录状态，此时的预扣记录的状态为 20(已确认)
            confirmDeductRecord(deductNo, realOrderNo);
            return realOrderNo;
        } catch (RuntimeException e) {
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            // 把库存预扣记录的状态和释放原因给补充
            failDeductRecord(deductNo, limitReason(e.getMessage()));
            throw e;
        }
    }

    private StockDeductRecordEntity getDeductRecordByRequestId(String requestId) {
        return stockDeductRecordMapper.selectOne(Wrappers.<StockDeductRecordEntity>lambdaQuery()
                .eq(StockDeductRecordEntity::getRequestId, requestId)
                .last("limit 1"));
    }

    private String handleOldDeductRecord(StockDeductRecordEntity oldRecord) {
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_CONFIRMED) &&
                StringUtils.hasText(oldRecord.getOrderNo())) {
            return oldRecord.getOrderNo();
        }
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_PRE_DEDUCTED)) {
            throw new BizException("请求正在处理中，请勿重复提交");
        }
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_FAILED)) {
            throw new BizException("该requestId对应的预约请求已失败，请更换requestId后重试");
        }
        throw new BizException("该requestId状态异常，请勿重复提交");
    }

    private void initStockCacheIfAbsent(Long stockItemId, String stockKey) {
        Boolean hasStockCache = stringRedisTemplate.hasKey(stockKey);
        if (Boolean.TRUE.equals(hasStockCache)) {
            return;
        }

        StockItemEntity stockItem = stockItemMapper.selectById(stockItemId);
        if (Objects.isNull(stockItem) || Objects.equals(stockItem.getDeleted(), 1)) {
            throw new BizException("库存项不存在");
        }
        if (!Objects.equals(stockItem.getStatus(), 1)) {
            throw new BizException("库存项未启用");
        }
        Integer availableStock = Objects.requireNonNullElse(stockItem.getAvailableStock(), 0);
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(availableStock));
    }

    private StockDeductRecordEntity buildPreDeductRecord(ResourceOrderCreateDto createDto, String deductNo,
                                                         LocalDateTime expireTime) {
        StockDeductRecordEntity deductRecord = new StockDeductRecordEntity();
        deductRecord.setDeductNo(deductNo);
        deductRecord.setUserId(createDto.getUserId());
        deductRecord.setResourceId(createDto.getResourceId());
        deductRecord.setStockItemId(createDto.getStockItemId());
        deductRecord.setQuantity(createDto.getQuantity());
        deductRecord.setRequestId(createDto.getRequestId());
        deductRecord.setStatus(STOCK_DEDUCT_STATUS_PRE_DEDUCTED);
        deductRecord.setExpireTime(expireTime);
        return deductRecord;
    }

    private CreateOrderDto buildCreateOrderDto(ResourceOrderCreateDto createDto, String orderNo, String deductNo,
                                               LocalDateTime expireTime) {
        CreateOrderDto createOrderDto = new CreateOrderDto();
        createOrderDto.setOrderNo(orderNo);
        createOrderDto.setUserId(createDto.getUserId());
        createOrderDto.setResourceId(createDto.getResourceId());
        createOrderDto.setStockItemId(createDto.getStockItemId());
        createOrderDto.setQuantity(createDto.getQuantity());
        createOrderDto.setRequestId(createDto.getRequestId());
        createOrderDto.setDeductNo(deductNo);
        createOrderDto.setExpireTime(expireTime);
        return createOrderDto;
    }

    private void confirmDeductRecord(String deductNo, String orderNo) {
        stockDeductRecordMapper.update(null, Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                .eq(StockDeductRecordEntity::getDeductNo, deductNo)
                .set(StockDeductRecordEntity::getOrderNo, orderNo)
                .set(StockDeductRecordEntity::getStatus, STOCK_DEDUCT_STATUS_CONFIRMED));
    }

    private void failDeductRecord(String deductNo, String releaseReason) {
        stockDeductRecordMapper.update(null, Wrappers.<StockDeductRecordEntity>lambdaUpdate()
                .eq(StockDeductRecordEntity::getDeductNo, deductNo)
                .set(StockDeductRecordEntity::getStatus, STOCK_DEDUCT_STATUS_FAILED)
                .set(StockDeductRecordEntity::getReleaseReason, releaseReason));
    }

    private String buildStockKey(Long stockItemId) {
        return "floworder:stock:" + stockItemId;
    }

    private String generateOrderNo() {
        return "FO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) +
                randomSuffix();
    }

    private String generateDeductNo() {
        return "FD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) +
                randomSuffix();
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String limitReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "创建订单失败";
        }
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }
}
