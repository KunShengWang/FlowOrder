package com.javaup.resource.service.strategy;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.exception.FlowOrderFrameException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ResourceOrderContext {

    public static final Map<String,ResourceOrderStrategy> MAP = new HashMap<>();

    @Resource
    private List<ResourceOrderStrategy> strategyList;

    @PostConstruct
    private void init(){
        for (ResourceOrderStrategy strategy : strategyList) {
            MAP.put(strategy.version(),strategy);
        }
    }

    public ResourceOrderStrategy get(String version){
        return Optional.ofNullable(MAP.get(version)).orElseThrow(() -> new FlowOrderFrameException(BaseCodeEnum.PROGRAM_ORDER_STRATEGY_NOT_EXIST));
    }
}
