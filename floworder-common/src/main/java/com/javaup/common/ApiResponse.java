package com.javaup.common;

import com.javaup.enums.BaseCodeEnum;
import lombok.Data;

@Data
public class ApiResponse<T> {

    private Integer code;

    private String message;

    private T data;

    public static <T> ApiResponse<T> success(){
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "success";
        return response;
    }

    public static <T> ApiResponse<T> success(T data){
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(BaseCodeEnum codeEnum){
        ApiResponse<T> response = new ApiResponse<>();
        response.code = codeEnum.getCode();
        response.message = codeEnum.getMessage();
        return response;
    }

    public static <T> ApiResponse<T> error(Integer code,String message){
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }
}
