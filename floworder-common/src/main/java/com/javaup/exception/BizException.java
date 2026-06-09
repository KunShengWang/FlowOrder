package com.javaup.exception;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.exception.base.BaseException;
import lombok.Getter;

@Getter
public class BizException extends BaseException {

  public BizException(String message) {
    super(message);
  }

  public BizException(Integer code,String message) {
    super(message);
    this.code = code;
    this.message = message;
  }

  public BizException(BaseCodeEnum codeEnum) {
    super(codeEnum);
  }
}
