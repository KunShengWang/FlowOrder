package com.javaup.resource.service.strategy.impl;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.enums.ResourceOrderVersionEnum;
import com.javaup.resource.service.ReservationRequestService;
import com.javaup.resource.service.strategy.ResourceOrderStrategy;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import static com.javaup.trace.TraceConstant.TRACE_ID;

@Service
public class ResourceOrderV8StrategyImpl
        implements ResourceOrderStrategy {

    private final ReservationRequestService requestService;

    public ResourceOrderV8StrategyImpl(
            ReservationRequestService requestService
    ) {
        this.requestService = requestService;
    }

    @Override
    public String createOrder(ResourceOrderCreateDto createDto) {
        /*
         * V8请求线程只完成最小参数校验和持久化。
         * 资格、窗口、Redis和MySQL库存处理由后续工作线程执行。
         */
        return requestService.submit(createDto, MDC.get(TRACE_ID));
    }

    @Override
    public String version() {
        return ResourceOrderVersionEnum.V8_VERSION.getVersion();
    }
}