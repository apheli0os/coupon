package com.mall.common.exception;

import com.mall.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常。由 GlobalExceptionHandler 统一转换为 R，避免 try/catch 污染业务代码。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }
}
