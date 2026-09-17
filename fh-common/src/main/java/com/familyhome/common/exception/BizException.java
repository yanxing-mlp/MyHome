package com.familyhome.common.exception;

import lombok.Getter;

/**
 * 业务异常。由 fh-boot 的全局异常处理器统一转成 {@code Result}。
 *
 * <p>只承载 code + message，不带 data——v4 取消了 {@code TAG_IN_USE}（改为级联解绑）之后，
 * 已经没有需要在错误响应里回传结构化数据的场景。真需要时再加，不要预留。
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String code, String message) {
        return new BizException(code, message);
    }

    /** 资源不存在，message 直接可展示 */
    public static BizException notFound(String code, String message) {
        return new BizException(code, message);
    }
}
