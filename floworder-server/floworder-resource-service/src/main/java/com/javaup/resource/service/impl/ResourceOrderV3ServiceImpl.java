package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderCreateMessage;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.StockLuaResultCodeEnum;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.redis.StockDeductLuaExecutor;
import com.javaup.resource.service.ResourceOrderV3Service;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

import static com.javaup.constant.OrderMqConstant.*;
import static com.javaup.constant.RedisConstant.FLOWORDER_STOCK;
import static com.javaup.enums.BaseCodeEnum.StockItem_NOT_EXIST;
import static com.javaup.enums.BaseCodeEnum.StockItem_NOT_OPEN;
import static com.javaup.trace.TraceConstant.TRACE_ID;

@Service
@Slf4j
public class ResourceOrderV3ServiceImpl implements ResourceOrderV3Service {

    private final Integer PRE_DEDUCTED  = 10;
    private final Integer ORDER_CREATED  = 20;
    private final Integer RELEASED  = 30;
    private final Integer FAILED  = 40;
    private final Integer MANUAL_REVIEW  = 50;
    private final Integer SOLD  = 60;

    private final Integer CREATE_MODE_ASYNC = 3;

    private final Integer STOCK_KEY_MISSING = -2;

    private final Integer DEFAULT_EXPIRE_MINUTES = 15;

    private final Integer OUTBOX_STATUS_NEW = 0;

    @Resource
    private StockDeductRecordMapper deductRecordMapper;

    @Resource
    private StockDeductLuaExecutor deductLuaExecutor;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StockDeductService deductService;

    /**
     * v3版本创建订单
     */
    @Override
    public String createOrder(ResourceOrderCreateDto createDto) {
        // 接口幂等
        StockDeductRecordEntity stockDeductRecordEntity = getDeductRecordByRequestId(createDto);
        if(stockDeductRecordEntity != null){
            return handleOldDeductRecord(createDto,stockDeductRecordEntity);
        }
        // 构建库存key
        String stockKey = FLOWORDER_STOCK + createDto.getStockItemId();
        // 扣减redis库存
        deductRedisCacheStock(stockKey,createDto);
        // 创建预扣订单
        String deductNo = generateDeductNo();
        // 创建订单
        String orderNo = generateOrderNo();
        // 创建messageId
        String messageId = UUID.randomUUID().toString().replace("-","");
        try{
            // 构建预扣订单
            StockDeductRecordEntity record = buildStockDeductRecord(createDto,deductNo,orderNo);
            // 构建订单创建mq消息
            OrderCreateMessage message = buildOrderCreateMessage(createDto,deductNo,orderNo,messageId);
            // 构建mq发件箱实体类
            MqOutboxEntity outbox = buildOrderCreateOutbox(message);
            // 库存预扣并保存mq消息
            deductService.preDeductAndSaxveOutbox(createDto,record,outbox);
        }catch (DuplicateKeyException e){
            // 安全的补充redis库存
            compensateRedisSafely(stockKey,createDto,e);
            stockDeductRecordEntity = getDeductRecordByRequestId(createDto);
            if(stockDeductRecordEntity != null){
                return handleOldDeductRecord(createDto,stockDeductRecordEntity);
            }
            throw e;
        } catch (RuntimeException e) {
            compensateRedisSafely(stockKey,createDto,e);
            throw e;
        }
        return orderNo;
    }

    /**
     * 根据requestId获取库存扣减记录
     */
    private StockDeductRecordEntity getDeductRecordByRequestId(ResourceOrderCreateDto createDto) {
        return deductRecordMapper.selectOne(
                Wrappers.<StockDeductRecordEntity>lambdaQuery()
                        .eq(StockDeductRecordEntity::getRequestId,createDto.getRequestId())
                        .last("limit 1")
        );
    }

    /**
     * 根据预扣记录进行下一步的操作
     */
    private String handleOldDeductRecord(ResourceOrderCreateDto createDto,StockDeductRecordEntity record) {
        // 判断是否是用相同的requestId去请求不同的商品
        boolean sameRequest = Objects.equals(createDto.getQuantity(), record.getQuantity())
                && Objects.equals(createDto.getRequestId(), record.getRequestId())
                && Objects.equals(createDto.getResourceId(), record.getResourceId())
                && Objects.equals(createDto.getStockItemId(), record.getStockItemId())
                && Objects.equals(createDto.getUserId(), record.getUserId());
        if(!sameRequest){
            throw new BizException("requestId已被其他请求使用，不能修改预约参数");
        }
        // 已被预扣
        if(Objects.equals(record.getStatus(),PRE_DEDUCTED)){
            // 判断是否是异步请求，如果是的话需要返回订单号
            if(Objects.equals(record.getCreateMode(),CREATE_MODE_ASYNC) && StringUtils.hasText(record.getOrderNo())){
                return record.getOrderNo();
            }
            throw new BizException("订单正在处理中，请勿重复提交");
        }
        // 已确认
        if(Objects.equals(record.getStatus(),ORDER_CREATED) && StringUtils.hasText(record.getOrderNo())){
            return record.getOrderNo();
        }
        // 创建失败
        if(Objects.equals(record.getStatus(),FAILED)){
            throw new BizException("订单创建失败");
        }
        // 人工确认
        if(Objects.equals(record.getStatus(),MANUAL_REVIEW)){
            throw new BizException("订单创建结果人工确认中，请勿重复提交");
        }
        throw new BizException("该requestId状态异常，请勿重复提交");
    }

    /**
     * 扣减redis库存
     */
    private void deductRedisCacheStock(String stockKey, ResourceOrderCreateDto createDto) {
        // 返回剩余库存
        Long remainStock = deductLuaExecutor.deduct(stockKey,createDto.getQuantity());
        if(remainStock == null){
            throw new BizException("Redis扣减结果为空");
        }
        StockLuaResultCodeEnum codeEnum = StockLuaResultCodeEnum.of(remainStock);
        // 扣减成功
        if(codeEnum == null){
            return;
        }
        // 没有库存缓存key
        if(Objects.equals(codeEnum.getCode(),STOCK_KEY_MISSING)){
            // 初始化redis缓存
            initRedisCacheStock(stockKey,createDto);
            // 再扣一次缓存
            remainStock = deductLuaExecutor.deduct(stockKey,createDto.getQuantity());
            if(remainStock == null){
                throw new BizException("Redis扣减结果为空");
            }
            codeEnum = StockLuaResultCodeEnum.of(remainStock);
        }
        if(Objects.nonNull(codeEnum)){
            throw new BizException(codeEnum);
        }
    }

    /**
     * 初始化redis缓存
     */
    private void initRedisCacheStock(String stockKey,ResourceOrderCreateDto createDto) {
        StockItemEntity stockItem = stockItemMapper.selectById(createDto.getStockItemId());
        // 库存不存在
        if(Objects.isNull(stockItem) || Objects.equals(stockItem.getDeleted(),1)){
            throw new BizException(StockItem_NOT_EXIST);
        }
        // 库存未启用
        if(!Objects.equals(stockItem.getStatus(),1)){
            throw new BizException(StockItem_NOT_OPEN);
        }
        Integer availableStock = Objects.requireNonNullElse(stockItem.getAvailableStock(), 0);
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey,String.valueOf(availableStock));
    }

    /**
     * 创建预扣订单
     */
    private String generateDeductNo() {
        return "FD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + randomSuffix();
    }

    /**
     * 创建订单
     */
    private String generateOrderNo() {
        return "FO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + randomSuffix();
    }

    /**
     * 随机前缀
     */
    private String randomSuffix(){
        return UUID.randomUUID().toString().replace("-","").substring(0,8);
    }

    private StockDeductRecordEntity buildStockDeductRecord(ResourceOrderCreateDto createDto, String deductNo, String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        StockDeductRecordEntity record = new StockDeductRecordEntity();
        record.setDeductNo(deductNo);
        record.setOrderNo(orderNo);
        record.setUserId(createDto.getUserId());
        record.setResourceId(createDto.getResourceId());
        record.setStockItemId(createDto.getStockItemId());
        record.setQuantity(createDto.getQuantity());
        record.setRequestId(createDto.getRequestId());
        record.setStatus(PRE_DEDUCTED);
        record.setCreateMode(CREATE_MODE_ASYNC);
        // 订单超时时间，不是MQ发送重试时间
        record.setExpireTime(now.plusMinutes(DEFAULT_EXPIRE_MINUTES));
        record.setRetryCount(0);
        record.setQueryErrorCount(0);
        // V3不由V2补偿任务查询订单
        record.setNextRetryTime(null);
        record.setLastError(null);
        record.setReleaseReason(null);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    /**
     * 构建订单创建mq消息
     */
    private OrderCreateMessage buildOrderCreateMessage(
            ResourceOrderCreateDto createDto, String deductNo, String orderNo, String messageId) {
        LocalDateTime now = LocalDateTime.now();
        CreateOrderDto createOrderDto = buildCreateOrderDto(createDto,orderNo,deductNo,now.plusMinutes(DEFAULT_EXPIRE_MINUTES));
        OrderCreateMessage orderCreateMessage = new OrderCreateMessage();
        orderCreateMessage.setMessageId(messageId);
        orderCreateMessage.setTraceId(MDC.get(TRACE_ID));
        orderCreateMessage.setEventType(ORDER_CREATE_COMMAND);
        orderCreateMessage.setOccurredAt(now);
        orderCreateMessage.setData(createOrderDto);
        return orderCreateMessage;
    }

    /**
     * 构建创建订单dto
     */
    private CreateOrderDto buildCreateOrderDto(
            ResourceOrderCreateDto createDto, String orderNo, String deductNo, LocalDateTime expireTime) {
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

    /**
     * 构建mq发件箱实体类
     */
    private MqOutboxEntity buildOrderCreateOutbox(OrderCreateMessage message) {
        LocalDateTime now = LocalDateTime.now();
        String content;
        try{
            content = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("订单序列化失败，deductNo = {},messageId = {}",
                    message.getData().getDeductNo(),
                    message.getMessageId(),
                    e);
            throw new BizException("订单创建消息序列化失败");
        }
        MqOutboxEntity outbox = new MqOutboxEntity();
        outbox.setMessageId(message.getMessageId());
        outbox.setProducerService(RESOURCE_SERVICE);
        outbox.setBizKey(message.getData().getDeductNo());
        outbox.setMessageType(message.getEventType());
        outbox.setExchangeName(ORDER_CREATE_EXCHANGE);
        outbox.setRoutingKey(ORDER_CREATE_ROUTING_KEY);
        outbox.setContent(content);
        outbox.setStatus(OUTBOX_STATUS_NEW);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(now);
        outbox.setClaimUntil(null);
        outbox.setLastError(null);
        outbox.setSentAt(null);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    /**
     * 安全的补充redis库存
     */
    private void compensateRedisSafely(String stockKey, ResourceOrderCreateDto createDto,RuntimeException originalException) {
       try{
           Long result = deductLuaExecutor.increment(stockKey, createDto.getQuantity());
           if(result == null){
               throw new BizException("Redis增加结果为空");
           }
           StockLuaResultCodeEnum codeEnum = StockLuaResultCodeEnum.of(result);
           // 补充成功
           if(codeEnum == null){
               return;
           }
           // 库存key不存在，不用管等待下一次新建订单的时候初始化redis
           if(Objects.equals(codeEnum.getCode(),STOCK_KEY_MISSING)){
               log.warn("恢复Redis库存时key不存在, stockKey={}", stockKey);
               return;
           }
           throw new BizException(codeEnum);
       }catch (RuntimeException compensateException){
           try{
               stringRedisTemplate.delete(stockKey);
           } catch (RuntimeException e) {
               compensateException.addSuppressed(e);
           }
           originalException.addSuppressed(compensateException);
           log.error(
                   "Redis库存补偿失败, stockKey={}, quantity={}",
                   stockKey,
                   createDto.getQuantity(),
                   originalException
           );
       }
    }
}
