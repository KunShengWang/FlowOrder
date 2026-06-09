package com.javaup.exception.base;

import com.javaup.enums.BaseCodeEnum;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    public Integer code;

    public String message;

    public BaseException(){
        super();
    }

    public BaseException(String message) {
        super(message);
    }

    public BaseException(BaseCodeEnum codeEnum){
        super(codeEnum.getMessage());
        this.code = codeEnum.getCode();
        this.message = codeEnum.getMessage();
    }
}
