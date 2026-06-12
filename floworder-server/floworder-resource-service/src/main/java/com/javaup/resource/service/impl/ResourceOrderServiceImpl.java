package com.javaup.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.javaup.client.OrderClient;
import com.javaup.common.ApiResponse;
import com.javaup.constant.RedisConstant;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.StockLuaResultCodeEnum;
import com.javaup.exception.BizException;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.mapper.StockDeductRecordMapper;
import com.javaup.resource.mapper.StockItemMapper;
import com.javaup.resource.redis.StockDeductLuaExecutor;
import com.javaup.resource.service.ResourceOrderService;
import com.javaup.resource.service.StockDeductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.javaup.enums.BaseCodeEnum.StockItem_NOT_EXIST;
import static com.javaup.enums.BaseCodeEnum.StockItem_NOT_OPEN;
import static com.javaup.enums.StockLuaResultCodeEnum.STOCK_CACHE_MISSING;

@Service
@Slf4j
public class ResourceOrderServiceImpl implements ResourceOrderService {

    private static final Integer STOCK_DEDUCT_STATUS_PRE_DEDUCTED = 10;

    private static final Integer STOCK_DEDUCT_STATUS_CONFIRMED = 20;

    private static final Integer STOCK_DEDUCT_STATUS_FAILED = 40;

    private static final Integer SUCCESS_CODE = 200;

    private static final Integer DEFAULT_EXPIRE_MINUTES = 15;

    private static final Integer STOCK_DEDUCT_STATUS_MANUAL_REVIEW = 50;

    @Resource
    private StockItemMapper stockItemMapper;

    @Resource
    private StockDeductRecordMapper stockDeductRecordMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderClient orderClient;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StockDeductService stockDeductService;

    @Resource
    private StockDeductLuaExecutor stockDeductLuaExecutor;

    /**
     * 并发场景下，相同的 requestId 同时请求，是数据库的唯一key做兜底和防御
     */
    @Override
    public String createV1(ResourceOrderCreateDto createDto) {
        // 按商品的库存id做锁，细粒度
        String lockKey = "floworder:lock:reservation:create:v1:stock:" + createDto.getStockItemId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try{
            // 尝试获取锁，如果3秒内没有获取到锁就直接失败，不阻塞；如果获取到锁就执行看门狗机制
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException("当前预约人数较多，请稍后重试");
            }
            return doCreateV1(createDto);
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new BizException("获取下单锁失败");
        }finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public String createV2(ResourceOrderCreateDto createDto) {
        // 保证幂等性
        // 根据 requestId 查看库存扣减记录，如果重复请求就把创建好的订单记录返回
        StockDeductRecordEntity oldRecord = getDeductRecordByRequestId(createDto.getRequestId());
        if (Objects.nonNull(oldRecord)) {
            // 根据库存预扣记录的状态去判断下一步的操作
            return handleOldDeductRecord(oldRecord,createDto);
        }
        // 构建库存key
        String stockKey = RedisConstant.FLOWORDER_STOCK + createDto.getStockItemId();
        // 扣减redis库存，如果扣减成功会返回库存剩余值
        // 有数据库requestId唯一索引做防御，不会引发多线程安全问题
        Long remainStock = deductRedisStockV2(createDto.getStockItemId(),stockKey,createDto.getQuantity());
        // 构造编号
        String orderNo = generateOrderNo();
        String deductNo = generateDeductNo();
        // 远程调用超时的过期时间为5秒
        // 这个过期时间应该是被下次确认时间给替代了
        LocalDateTime reconcileTime = LocalDateTime.now().plusSeconds(5);
        // 订单超时未支付时间
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15);
        // 构建库存预扣记录，此时的预扣记录的状态为 10(已预扣)
        StockDeductRecordEntity deductRecord = buildPreDeductRecord(createDto, deductNo, reconcileTime);
        // 进行库存预扣
        try{
            // 一个事务中完成：
            // 1. 插入预扣记录
            // 2. available_stock -> locked_stock
            stockDeductService.preDeduct(createDto,deductRecord);
        }catch (DuplicateKeyException e){// 插入预扣记录出现重复key操作，说明之前已经下过单了
            // 进行redis的补偿
            compensateRedisSafely(stockKey,createDto.getQuantity(),e);
            // 根据requestId查询库存预扣记录
            oldRecord = getDeductRecordByRequestId(createDto.getRequestId());
            if (Objects.nonNull(oldRecord)) {
                // 根据库存预扣记录的状态去判断下一步的操作
                return handleOldDeductRecord(oldRecord,createDto);
            }
            throw e;
        } catch (RuntimeException e) {
            compensateRedisSafely(stockKey,createDto.getQuantity(),e);
            throw e;
        }
        // 执行远程下单逻辑
        // 构建创建订单dto
        CreateOrderDto orderDto = buildCreateOrderDto(createDto, orderNo, deductNo, expireTime);
        ApiResponse<String> response;
        try{
            response = orderClient.create(orderDto);
        } catch (RuntimeException e) {
            // Feign 的网络、超时等异常
            // 此时不应该是为远程订单创建失败，需要进一步的排查
            log.warn("订单创建调用异常，结果未知, requestId={}",
                    createDto.getRequestId(), e);
            return confirmRemoteOrderResult(createDto,orderDto);
        }
        // 如果response为空，防御性编程
        if(response == null || response.getCode() == null){
            return confirmRemoteOrderResult(createDto,orderDto);
        }
        // 明确订单创建失败,执行库存回退
        if(!Objects.equals(response.getCode(),SUCCESS_CODE)){
            String cause =  StringUtils.hasText(response.getMessage()) ? response.getMessage() : "订单服务明确返回创建失败";
            BizException originalException = new BizException(response.getCode(), cause);
            try{
                // 库存失败释放
                releaseForCreateOrderFailure(createDto,deductNo,cause,stockKey,originalException);
            } catch (RuntimeException compensateException) {
                originalException.addSuppressed(compensateException);
                log.error(
                        "订单创建失败后的库存补偿失败, requestId={}, deductNo={}, stockItemId={}",
                        createDto.getRequestId(),
                        deductNo,
                        createDto.getStockItemId(),
                        originalException
                );
            }
            throw originalException;
        }
        // 订单创建成功
        if (!StringUtils.hasText(response.getData())) {
            return confirmRemoteOrderResult(createDto, orderDto);
        }
        String realOrderNo = response.getData();
        try{
            stockDeductService.confirm(deductNo,realOrderNo);
        } catch (RuntimeException e) {
            /*
             * 订单已经存在，confirm失败时绝对不能释放库存。
             * 保持PRE_DEDUCTED，由补偿任务重新确认。
             */
            throw new BizException("订单已创建，库存状态确认处理中");
        }
        return realOrderNo;
    }

    /**
     * 创建订单失败的库存释放
     */
    private void releaseForCreateOrderFailure(ResourceOrderCreateDto orderDto, String deductNo, String cause, String stockKey,RuntimeException originalException) {
        // 数据库的库存释放
        stockDeductService.release(orderDto,deductNo,cause);
        // redis的库存释放
        compensateRedisSafely(stockKey,orderDto.getQuantity(),originalException);
    }

    /**
     * 确认远程调用结果
     */
    private String confirmRemoteOrderResult(ResourceOrderCreateDto createDto,CreateOrderDto orderDto) {
        ApiResponse<OrderQueryDto> response;
        try{
             response = orderClient.queryByRequestId(createDto.getRequestId());
        } catch (RuntimeException e) {
            // 可能还会出现远程调用网络超时等异常，需要进一步的排查
            log.warn("查询订单结果失败, requestId={}",
                    createDto.getRequestId(), e);
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // response可能为空，只是作为防御性设置，可能会发生
        if(response == null){
            log.error(
                    "订单查询返回空响应, requestId={}",
                    createDto.getRequestId()
            );
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // 响应码不是成功的200,这是查询出现了异常，并不代表创建任务失败，所以可以重试
        if(!Objects.equals(response.getCode(),SUCCESS_CODE)){
            log.warn(
                    "订单查询业务失败, requestId={}, code={}, message={}",
                    createDto.getRequestId(),
                    response.getCode(),
                    response.getMessage()
            );
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // 响应的response可能为空，只是作为防御性设置，可能会发生
        if(response.getData() == null){
            log.error(
                    "订单查询成功但data为空, requestId={}",
                    createDto.getRequestId()
            );
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        OrderQueryDto data = response.getData();
        // 没有查询到订单，刚调用完orderClient.create(orderDto)事务可能还未提交
        if(!Boolean.TRUE.equals(data.getExists())){
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        if (!StringUtils.hasText(data.getOrderNo())) {
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // 查到订单
        try{
            // 确认订单
            // 是否需要把订单的状态改为20
            stockDeductService.confirm(orderDto.getDeductNo(),data.getOrderNo());
        } catch (RuntimeException e) {
            // 订单存在，禁止释放
            throw new BizException("订单已创建，库存状态确认处理中");
        }
        return data.getOrderNo();
    }

    /**
     * 扣减redis库存
     */
    private Long deductRedisStockV2(Long stockItemId, String stockKey, Integer quantity) {
        // lua 脚本扣减 redis 库存
        Long result = stockDeductLuaExecutor.deduct(stockKey,quantity);
        if (result == null) {
            throw new BizException("Redis库存扣减结果为空");
        }
        StockLuaResultCodeEnum luaResultCode = StockLuaResultCodeEnum.of(result);
        // 判断下是否是库存缓存不存在
        if(luaResultCode == STOCK_CACHE_MISSING){
            // 初始化redis缓存并重试一次扣减redis库存
            initStockCacheIfAbsent(stockItemId,stockKey);
            result = stockDeductLuaExecutor.deduct(stockKey,quantity);
            if (result == null) {
                throw new BizException("Redis库存扣减结果为空");
            }
            luaResultCode = StockLuaResultCodeEnum.of(result);
        }
        // 出现错误
        if(Objects.nonNull(luaResultCode)){
            throw new BizException(luaResultCode);
        }
        // 返回库存剩余值
        return result;
    }

    /**
     * redis库存恢复
     */
    private void compensateRedisSafely(String stockKey, Integer quantity, RuntimeException originalException) {
        try {
            Long result = stockDeductLuaExecutor.increment(stockKey, quantity);
            if (result == null) {
                throw new BizException("Redis库存恢复结果为空");
            }
            StockLuaResultCodeEnum resultCode = StockLuaResultCodeEnum.of(result);
            if (resultCode == null) {
                // 返回非负库存，恢复成功
                return;
            }
            if (resultCode == StockLuaResultCodeEnum.STOCK_KEY_MISSING) {
                /*
                 * key 已经不存在，不要使用 INCRBY 创建一个错误库存。
                 * 保持缓存缺失，下次请求从 MySQL 重新初始化。
                 */
                log.warn("恢复Redis库存时key不存在, stockKey={}", stockKey);
                return;
            }
            throw new BizException(resultCode);
        } catch (RuntimeException compensateException) {
            try{
                stringRedisTemplate.delete(stockKey);
            }catch (RuntimeException deleteException){
                compensateException.addSuppressed(deleteException);
            }
            originalException.addSuppressed(compensateException);
            log.error(
                    "Redis库存补偿失败, stockKey={}, quantity={}",
                    stockKey,
                    quantity,
                    originalException
            );
            // 这里不会抛异常，因为这里的异常是挂在调用方的异常上的，调用方会抛出异常
        }
    }

    /**
     * V1版本创建订单
     */
    public String doCreateV1(ResourceOrderCreateDto createDto) {
        // 根据 requestId 查看库存扣减记录，如果重复请求就把创建好的订单记录返回
        StockDeductRecordEntity oldRecord = getDeductRecordByRequestId(createDto.getRequestId());
        if (Objects.nonNull(oldRecord)) {
            // 根据库存预扣记录的状态去判断下一步的操作
            return handleOldDeductRecord(oldRecord,createDto);
        }
        // 构造编号
        String orderNo = generateOrderNo();
        String deductNo = generateDeductNo();
        // 订单超时未支付的过期时间为15分钟
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(DEFAULT_EXPIRE_MINUTES);
        // 远程调用超时的过期时间为5秒
        LocalDateTime reconcileTime = LocalDateTime.now().plusSeconds(5);
        // 构造 redis 的 key
        String stockKey = buildStockKey(createDto.getStockItemId());
        // 初始化 redis 库存，把数据库中的商品库存放到redis中
        initStockCacheIfAbsent(createDto.getStockItemId(), stockKey);
        // 扣减 redis 库存，返回剩余的库存
        Long remainStock = stringRedisTemplate.opsForValue().decrement(stockKey, createDto.getQuantity());
        if (Objects.isNull(remainStock)) {
            throw new BizException("Redis扣减库存失败");
        }
        // 如果库存被扣成负数了
        if (remainStock < 0) {
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            throw new BizException("库存不足");
        }
        // 构建库存预扣记录，此时的预扣记录的状态为 10(已预扣)
        StockDeductRecordEntity deductRecord = buildPreDeductRecord(createDto, deductNo, reconcileTime);
        try {
            // 一个事务中完成：
            // 1. 插入预扣记录
            // 2. available_stock -> locked_stock
            stockDeductService.preDeduct(createDto, deductRecord);
        } catch (DuplicateKeyException e) {
            // Redis之前已经扣过，但是MySQL事务因为唯一索引冲突已经回滚，
            // 所以这里只需要恢复Redis库存
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            // 根据requestId查询库存预扣记录
            StockDeductRecordEntity oldRecord1 = getDeductRecordByRequestId(createDto.getRequestId());
            if (Objects.nonNull(oldRecord1)) {
                // 根据库存预扣记录的状态去判断下一步的操作
                return handleOldDeductRecord(oldRecord1,createDto);
            }
            throw e;
        } catch (RuntimeException e) {
            // MySQL库存不足或其他数据库异常时，
            // preDeduct事务会自动回滚，但Redis不属于MySQL事务，需要手动恢复
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
            throw e;
        }
        // 上面是创建预扣订单，下面是真正的下单，后续还需要补充延时队列
        // 构建创建订单dto
        CreateOrderDto orderDto = buildCreateOrderDto(createDto, orderNo, deductNo, expireTime);
        ApiResponse<String> createOrderResponse;
        // 第一段：只负责远程调用
        try {
            createOrderResponse = orderClient.create(orderDto);
        } catch (RuntimeException callException) {
            // 网络超时、连接断开：订单可能已经创建，不能释放库存
            return reconcileOrderResult(createDto, deductNo);
        }
        // 然后判断返回结果
        // 返回null也属于结果未知，不能直接释放
        if (Objects.isNull(createOrderResponse)) {
            return reconcileOrderResult(createDto, deductNo);
        }
        // 第二段：order-service明确返回业务失败
        if (!Objects.equals(createOrderResponse.getCode(), SUCCESS_CODE)) {
            String reason = StringUtils.hasText(createOrderResponse.getMessage()) ? createOrderResponse.getMessage() : "订单服务明确返回创建失败";
            BizException originalException = new BizException(reason);
            try {
                // 库存失败释放
                releaseForDefiniteFailure(createDto, deductNo, stockKey, reason);
            } catch (RuntimeException compensateException) {
                // 保留原异常，把补偿异常作为附加异常，也就是把 compensateException 作为“被抑制的异常”挂到 originalException 上，避免异常信息丢失。
                originalException.addSuppressed(compensateException);
                log.error(
                        "订单创建失败后的库存补偿失败, requestId={}, deductNo={}, stockItemId={}",
                        createDto.getRequestId(),
                        deductNo,
                        createDto.getStockItemId(),
                        originalException
                );
            }
            // 对外仍然抛最初的订单创建失败异常
            throw originalException;
        }
        // 成功后单独确认
        String realOrderNo = StringUtils.hasText(createOrderResponse.getData()) ? createOrderResponse.getData() : orderNo;
        // 第三段：订单已经明确创建成功
        try {
            stockDeductService.confirm(deductNo, realOrderNo);
        } catch (RuntimeException confirmException) {
            /*
             * 订单已经存在，confirm失败时绝对不能释放库存。
             * 保持PRE_DEDUCTED，由补偿任务重新确认。
             */
            throw new BizException("订单已创建，库存状态确认处理中");
        }
        return realOrderNo;
    }

    /**
     * 根据 requestId 查看库存扣减记录
     */
    private StockDeductRecordEntity getDeductRecordByRequestId(String requestId) {
        return stockDeductRecordMapper.selectOne(Wrappers.<StockDeductRecordEntity>lambdaQuery()
                .eq(StockDeductRecordEntity::getRequestId, requestId)
                .last("limit 1"));
    }

    /**
     * 根据库存预扣记录的状态去判断下一步的操作
     */
    private String handleOldDeductRecord(StockDeductRecordEntity oldRecord, ResourceOrderCreateDto dto) {
        // 防止如果客户端错误地使用同一个 requestId 请求不同商品或数量，也会返回旧订单
        boolean sameRequest =
                Objects.equals(oldRecord.getUserId(), dto.getUserId())
                        && Objects.equals(oldRecord.getResourceId(), dto.getResourceId())
                        && Objects.equals(oldRecord.getStockItemId(), dto.getStockItemId())
                        && Objects.equals(oldRecord.getQuantity(), dto.getQuantity());

        if (!sameRequest) {
            throw new BizException("requestId已被其他请求使用，不能修改预约参数");
        }
        // 库存扣成功了，已确认状态
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_CONFIRMED) &&
                StringUtils.hasText(oldRecord.getOrderNo())) {
            return oldRecord.getOrderNo();
        }
        // 已预扣
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_PRE_DEDUCTED)) {
            throw new BizException("请求正在处理中，请勿重复提交");
        }
        // 扣减失败
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_FAILED)) {
            throw new BizException("该requestId对应的预约请求已失败，请更换requestId后重试");
        }
        if (Objects.equals(oldRecord.getStatus(), STOCK_DEDUCT_STATUS_MANUAL_REVIEW)) {
            throw new BizException("订单创建结果人工确认中，请勿重复提交");
        }
        throw new BizException("该requestId状态异常，请勿重复提交");
    }

    /**
     * 如果redis中没有库存的缓存，就需要初始化redis
     */
    private void initStockCacheIfAbsent(Long stockItemId,String stockKey){
        // 没有库存的缓存，需要查数据库获取
        StockItemEntity stockItemEntity = stockItemMapper.selectById(stockItemId);
        // 库存项不存在
        if(Objects.isNull(stockItemEntity) || Objects.equals(stockItemEntity.getDeleted(),1)){
            throw new BizException(StockItem_NOT_EXIST);
        }
        // 库存项未启动
        if(!Objects.equals(stockItemEntity.getStatus(),1)){
            throw new BizException(StockItem_NOT_OPEN);
        }
        // 初始化redis
        Integer availableStock = Objects.requireNonNullElse(stockItemEntity.getAvailableStock(), 0);
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(availableStock));
    }

    /**
     * 构建库存预扣记录，此时的预扣记录的状态为 10(已预扣)
     */
    private StockDeductRecordEntity buildPreDeductRecord(ResourceOrderCreateDto createDto, String deductNo, LocalDateTime expireTime) {
        StockDeductRecordEntity deductRecord = new StockDeductRecordEntity();
        deductRecord.setDeductNo(deductNo);
        deductRecord.setUserId(createDto.getUserId());
        deductRecord.setResourceId(createDto.getResourceId());
        deductRecord.setStockItemId(createDto.getStockItemId());
        deductRecord.setQuantity(createDto.getQuantity());
        deductRecord.setRequestId(createDto.getRequestId());
        deductRecord.setStatus(STOCK_DEDUCT_STATUS_PRE_DEDUCTED);
        deductRecord.setExpireTime(expireTime);
        deductRecord.setRetryCount(0);
        deductRecord.setNextRetryTime(LocalDateTime.now().plusSeconds(5));
        deductRecord.setLastError(null);
        return deductRecord;
    }

    /**
     * 构建创建订单dto
     */
    private CreateOrderDto buildCreateOrderDto(ResourceOrderCreateDto createDto, String orderNo, String deductNo, LocalDateTime expireTime) {
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
     * 明确失败的释放方法
     */
    private void releaseForDefiniteFailure(ResourceOrderCreateDto createDto, String deductNo, String stockKey, String reason) {
        // 先通过本地事务恢复MySQL库存并修改预扣状态
        stockDeductService.release(createDto, deductNo, limitReason(reason));
        try {
            // MySQL成功后恢复Redis
            stringRedisTemplate.opsForValue().increment(stockKey, createDto.getQuantity());
        } catch (RuntimeException incrementException) {
            // 防止 Redis 删除异常覆盖 Redis 恢复异常
            try{
                /*
                 * MySQL已经恢复，Redis恢复失败。
                 * 删除缓存，下一次请求从MySQL重新初始化。
                 */
                stringRedisTemplate.delete(stockKey);
            }catch (RuntimeException deleteException) {
                incrementException.addSuppressed(deleteException);
            }
            throw incrementException;
        }
    }

    /**
     * 异常确认方法
     */
    private String reconcileOrderResult(ResourceOrderCreateDto createDto, String deductNo) {
        ApiResponse<OrderQueryDto> response;
        try {
            // 查询订单
            response = orderClient.queryByRequestId(createDto.getRequestId());
        } catch (RuntimeException queryException) {
            // 查询也失败，保持PRE_DEDUCTED
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // 没有查询到订单结果
        if (response == null
                || !Objects.equals(response.getCode(), SUCCESS_CODE)
                || response.getData() == null) {
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }

        OrderQueryDto result = response.getData();
        if (!Boolean.TRUE.equals(result.getExists())) {
            /*
             * 刚调用完就查不到，不代表订单一定没创建，
             * 可能订单事务还未提交，因此暂时不释放。
             */
            throw new BizException("订单创建结果确认中，请勿重复提交");
        }
        // 查询到了订单结果，确认订单记录表
        try {
            stockDeductService.confirm(deductNo, result.getOrderNo());
        } catch (RuntimeException confirmException) {
            // 订单存在，禁止释放
            throw new BizException("订单已创建，库存状态确认处理中");
        }
        return result.getOrderNo();
    }

    /**
     * 构造 redis 的 key
     */
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
