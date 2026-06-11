package com.javaup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.javaup.dto.CreateOrderDto;
import com.javaup.dto.OrderQueryDto;
import com.javaup.entity.ReservationOrderEntity;
import com.javaup.exception.BizException;
import com.javaup.mapper.ReservationOrderMapper;
import com.javaup.service.OrderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class OrderServiceImpl extends ServiceImpl<ReservationOrderMapper, ReservationOrderEntity> implements OrderService {

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
            return oldOrder.getOrderNo();
        }

        ReservationOrderEntity order = new ReservationOrderEntity();
        order.setOrderNo(createOrderDto.getOrderNo());
        order.setUserId(createOrderDto.getUserId());
        order.setResourceId(createOrderDto.getResourceId());
        order.setStockItemId(createOrderDto.getStockItemId());
        order.setQuantity(createOrderDto.getQuantity());
        order.setStatus(10);
        order.setRequestId(createOrderDto.getRequestId());
        order.setDeductNo(createOrderDto.getDeductNo());
        order.setExpireTime(createOrderDto.getExpireTime());
        order.setVersion(0);
        order.setDeleted(0);

        try {
            save(order);
        } catch (DuplicateKeyException e) {
            ReservationOrderEntity existOrder = getByRequestId(createOrderDto.getRequestId());
            if (Objects.nonNull(existOrder)) {
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
            throw new BizException("创建订单参数不能为空");
        }
        if (!StringUtils.hasText(createOrderDto.getOrderNo())) {
            throw new BizException("订单号不能为空");
        }
        if (Objects.isNull(createOrderDto.getUserId())) {
            throw new BizException("用户ID不能为空");
        }
        if (Objects.isNull(createOrderDto.getResourceId())) {
            throw new BizException("资源ID不能为空");
        }
        if (Objects.isNull(createOrderDto.getStockItemId())) {
            throw new BizException("库存项ID不能为空");
        }
        if (Objects.isNull(createOrderDto.getQuantity()) || createOrderDto.getQuantity() <= 0) {
            throw new BizException("预约数量非法");
        }
        if (!StringUtils.hasText(createOrderDto.getRequestId())) {
            throw new BizException("requestId不能为空");
        }
    }
}
