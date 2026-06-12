package com.javaup.exception.base;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.enums.StockLuaResultCodeEnum;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    public Integer code;

    public BaseException(){
        super();
    }

    public BaseException(String message) {
        super(message);
    }

    public BaseException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BaseException(BaseCodeEnum codeEnum){
        super(codeEnum.getMessage());
        this.code = codeEnum.getCode();
    }

    public BaseException(StockLuaResultCodeEnum codeEnum) {
        super(codeEnum.getMessage());
        this.code = codeEnum.getCode();
    }
}
