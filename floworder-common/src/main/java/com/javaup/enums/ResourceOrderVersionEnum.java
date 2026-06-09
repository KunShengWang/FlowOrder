package com.javaup.enums;

import lombok.Getter;

@Getter
public enum ResourceOrderVersionEnum {

    V1_VERSION("v1","v1购买策略版本",1);

    private final String version;

    private final String msg;

    private final Integer value;

    ResourceOrderVersionEnum(String version,String msg,Integer value){
        this.version = version;
        this.msg = msg;
        this.value = value;
    }

}
