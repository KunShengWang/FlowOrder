package com.javaup.resource.exception;

import com.javaup.exception.BizException;

public class InstantStockMismatchException extends BizException {

    public InstantStockMismatchException(String message) {
        super(message);
    }
}
