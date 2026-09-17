package com.familyhome.common.result;

import com.familyhome.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 统一响应体。
 *
 * <p>约定（见方案 §5.2）：HTTP 状态码表达传输语义（400/404/413/500），
 * 业务错误码放 body 的 {@code code}，成功固定为 {@code "0"}。
 *
 * @param <T> 业务数据类型
 */
@Getter
public class Result<T> {

    /** 错误码，成功为 "0" */
    private final String code;

    /** 提示文案，成功为 "success"，失败为可直接展示给用户的中文 */
    private final String message;

    /** 业务数据，失败时为 null */
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS, "success", data);
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.SUCCESS, "success", null);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(code, message, null);
    }

    public boolean isSuccess() {
        return ErrorCode.SUCCESS.equals(this.code);
    }
}
