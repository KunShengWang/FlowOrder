package com.javaup.exception;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.enums.StockLuaResultCodeEnum;
import com.javaup.exception.base.BaseException;
import lombok.Getter;

@Getter
public class BizException extends BaseException {

  public BizException(String message) {
    super(BaseCodeEnum.BUSINESS_ERROR.getCode(), message);
  }

  public BizException(Integer code,String message) {
    super(code, message);
  }

  public BizException(BaseCodeEnum codeEnum) {
    super(codeEnum);
  }

  public BizException(StockLuaResultCodeEnum codeEnum) {
    super(codeEnum);
  }
}
