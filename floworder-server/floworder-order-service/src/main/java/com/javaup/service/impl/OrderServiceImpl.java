package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.javaup.dto.CreateOrderDto;
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
import java.util.Objects;

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
        order.setStatus(RESERVED.getCode());
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

    /**
     * 根据 requestId 查询预约订单表
     */
    private ReservationOrderEntity getByRequestId(String requestId) {
        return getOne(Wrappers.<ReservationOrderEntity>lambdaQuery()
                .eq(ReservationOrderEntity::getRequestId, requestId)
                .last("limit 1"));
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