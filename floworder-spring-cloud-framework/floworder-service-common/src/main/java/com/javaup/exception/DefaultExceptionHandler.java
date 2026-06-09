package com.javaup.exception;

import com.javaup.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class DefaultExceptionHandler {

    /**
     * 业务异常
     * */
    @ExceptionHandler(value = BizException.class)
    public ApiResponse<String> toolkitExceptionHandler(HttpServletRequest request, BizException bizException) {
        log.error("业务异常 错误信息 : {} method : {} url : {} query : {} ", bizException.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), bizException);
        return ApiResponse.error(bizException.getCode(), bizException.getMessage());
    }

    private String getRequestUrl(HttpServletRequest request) {
        return request.getRequestURL().toString();
    }

    private String getRequestQuery(HttpServletRequest request){
        return request.getQueryString();
    }
}
