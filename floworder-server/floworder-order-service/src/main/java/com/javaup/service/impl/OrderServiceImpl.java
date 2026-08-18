package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderFactBatchRequest;
import com.javaup.dto.OrderFactBatchResult;
import com.javaup.dto.OrderFactItemDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.OrderStatusLogMapper;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import com.javaup.entity.OrderStatusLogEntity;

import java.time.LocalDateTime;

import static com.javaup.enums.BaseCodeEnum.*;
import static com.javaup.enums.OrderEventEnum.CREATE;
import static com.javaup.enums.OrderOperatorTypeEnum.SYSTEM;
import static com.javaup.enums.OrderStatusEnum.INIT;
import static com.javaup.enums.OrderStatusEnum.RESERVED;

@Service
public class OrderServiceImpl extends ServiceImpl<ReservationOrderMapper, ReservationOrderEntity> implements OrderService {

    @Resource
    private OrderStatusLogMapper statusLogMapper;

    /**
     * 创建订单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(CreateOrderDto createOrderDto) {
        // 参数校验
        checkCreateOrderDto(createOrderDto);
        // 查看是否创建过订单
        ReservationOrderEntity oldOrder = getByRequestId(createOrderDto.getRequestId());
        if (Objects.nonNull(oldOrder)) {
            checkIdempotentConsistency(oldOrder, createOrderDto);
            return oldOrder.getOrderNo();
        }
        checkNewOrderExpireTime(createOrderDto.getExpireTime());
        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setOrderNo(createOrderDto.getOrderNo());
        order.setUserId(createOrderDto.getUserId());
        order.setResourceId(createOrderDto.getResourceId());
        order.setStockItemId(createOrderDto.getStockItemId());
        order.setQuantity(createOrderDto.getQuantity());
        order.setStatus(RESERVED.getCode());// 已预约
        order.setRequestId(createOrderDto.getRequestId());
        order.setDeductNo(createOrderDto.getDeductNo());
        order.setExpireTime(createOrderDto.getExpireTime());
        order.setVersion(0);
        order.setDeleted(0);

        try {
            save(order);
            saveCreateStatusLog(order.getOrderNo());
        } catch (DuplicateKeyException e) {
            ReservationOrderEntity existOrder = getByRequestId(createOrderDto.getRequestId());
            if (Objects.nonNull(existOrder)) {
                checkIdempotentConsistency(existOrder, createOrderDto);
                return existOrder.getOrderNo();
            }
            throw e;
        }
        return order.getOrderNo();
    }

    /**
     * 订单查询
     */
    @Override
    public OrderQueryDto queryByRequestId(String requestId) {
        // 根据 requestId 查询预约订单表
        ReservationOrderEntity order = getByRequestId(requestId);
        OrderQueryDto result = new OrderQueryDto();
        result.setExists(order != null);
        if (order != null) {
            result.setOrderNo(order.getOrderNo());
            result.setStatus(order.getStatus());
        }
        return result;
    }

    @Override
    public OrderFactBatchResult queryFacts(OrderFactBatchRequest request) {
        List<String> requestIds = normalizeRequestIds(request);
        List<ReservationOrderEntity> orders = list(Wrappers.<ReservationOrderEntity>lambdaQuery()
                .in(ReservationOrderEntity::getRequestId, requestIds)
                .orderByAsc(ReservationOrderEntity::getRequestId));

        Map<String, ReservationOrderEntity> byRequestId = new LinkedHashMap<>();
        for (ReservationOrderEntity order : orders) {
            byRequestId.putIfAbsent(order.getRequestId(), order);
        }

        List<OrderFactItemDto> items = requestIds.stream()
                .map(requestId -> toFactItem(requestId, byRequestId.get(requestId)))
                .toList();
        List<String> missingRequestIds = items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getExists()))
                .map(OrderFactItemDto::getRequestId)
                .toList();

        OrderFactBatchResult result = new OrderFactBatchResult();
        result.setObservedAt(LocalDateTime.now());
        result.setItems(items);
        result.setMissingRequestIds(missingRequestIds);
        return result;
    }

    /**
     * 根据 requestId 查询预约订单表
     */
    private ReservationOrderEntity getByRequestId(String requestId) {
        return getOne(Wrappers.<ReservationOrderEntity>lambdaQuery()
                .eq(ReservationOrderEntity::getRequestId, requestId)
                .last("limit 1"));
    }

    private List<String> normalizeRequestIds(OrderFactBatchRequest request) {
        if (request == null || request.getRequestIds() == null) {
            throw new BizException("requestIds must not be empty");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String requestId : request.getRequestIds()) {
            if (!StringUtils.hasText(requestId)) {
                throw new BizException("requestId must not be blank");
            }
            normalized.add(requestId.trim());
        }
        if (normalized.isEmpty() || normalized.size() > 100) {
            throw new BizException("requestIds size must be between 1 and 100");
        }
        return List.copyOf(normalized);
    }

    private OrderFactItemDto toFactItem(String requestId, ReservationOrderEntity order) {
        OrderFactItemDto item = new OrderFactItemDto();
        item.setRequestId(requestId);
        item.setExists(order != null);
        if (order != null) {
            item.setOrderNo(order.getOrderNo());
            item.setDeductNo(order.getDeductNo());
            item.setStatus(order.getStatus());
            item.setUpdatedAt(order.getUpdatedAt());
        }
        return item;
    }

    private void checkCreateOrderDto(CreateOrderDto createOrderDto) {
        if (Objects.isNull(createOrderDto)) {
            throw new BizException(ORDER_CREATE_PARAM_EMPTY);
        }
        if (!StringUtils.hasText(createOrderDto.getOrderNo())) {
            throw new BizException(ORDER_NO_EMPTY);
        }
        if (Objects.isNull(createOrderDto.getUserId())) {
            throw new BizException(ORDER_USER_ID_EMPTY);
        }
        if (Objects.isNull(createOrderDto.getResourceId())) {
            throw new BizException(ORDER_RESOURCE_ID_EMPTY);
        }
        if (Objects.isNull(createOrderDto.getStockItemId())) {
            throw new BizException(ORDER_STOCK_ITEM_ID_EMPTY);
        }
        if (Objects.isNull(createOrderDto.getQuantity()) || createOrderDto.getQuantity() <= 0) {
            throw new BizException(ORDER_QUANTITY_INVALID);
        }
        if (!StringUtils.hasText(createOrderDto.getRequestId())) {
            throw new BizException(ORDER_REQUEST_ID_EMPTY);
        }
        if (!StringUtils.hasText(createOrderDto.getDeductNo())) {
            throw new BizException(ORDER_DEDUCT_NO_EMPTY);
        }
        if (createOrderDto.getExpireTime() == null){
            throw new BizException(ORDER_EXPIRE_TIME_INVALID);
        }
    }

    private void checkNewOrderExpireTime(LocalDateTime expireTime) {
        if (!expireTime.isAfter(LocalDateTime.now())) {
            throw new BizException(ORDER_EXPIRE_TIME_INVALID);
        }
    }

    private void saveCreateStatusLog(String orderNo) {
        OrderStatusLogEntity log = new OrderStatusLogEntity();
        log.setOrderNo(orderNo);
        log.setFromStatus(INIT.getCode());
        log.setToStatus(RESERVED.getCode());
        log.setEvent(CREATE.getCode());
        log.setOperatorType(SYSTEM.getCode());
        log.setRemark("订单创建成功");
        log.setCreatedAt(LocalDateTime.now());

        if (statusLogMapper.insert(log) != 1) {
            throw new IllegalStateException("订单创建状态日志保存失败");
        }
    }

    private void checkIdempotentConsistency(ReservationOrderEntity oldOrder, CreateOrderDto dto) {
        boolean same =
                Objects.equals(oldOrder.getOrderNo(), dto.getOrderNo())
                        && Objects.equals(oldOrder.getUserId(), dto.getUserId())
                        && Objects.equals(oldOrder.getResourceId(), dto.getResourceId())
                        && Objects.equals(oldOrder.getStockItemId(), dto.getStockItemId())
                        && Objects.equals(oldOrder.getQuantity(), dto.getQuantity())
                        && Objects.equals(oldOrder.getDeductNo(), dto.getDeductNo())
                        && Objects.equals(oldOrder.getExpireTime().truncatedTo(ChronoUnit.SECONDS), dto.getExpireTime().truncatedTo(ChronoUnit.SECONDS));
        if (!same) {
            throw new BizException(ORDER_IDEMPOTENT_CONFLICT);
        }
    }
}
