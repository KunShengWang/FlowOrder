package com.javaup.exception;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.exception.base.BaseException;
import lombok.Getter;

@Getter
public class FlowOrderFrameException extends BaseException {

    public FlowOrderFrameException(){
        super();
    }

    public FlowOrderFrameException(Integer code,String message) {
        super(message);
        this.code = code;
    }

    public FlowOrderFrameException(BaseCodeEnum codeEnum) {
        super(codeEnum);
    }
}
