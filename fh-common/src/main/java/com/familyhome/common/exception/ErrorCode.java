package com.familyhome.common.exception;

/**
 * 业务错误码常量。
 *
 * <p>用字符串常量而不是数字码段——家庭项目没必要维护数字分配规则，
 * 字符串在日志和前端排查时都能直接读懂（见方案 §5.2）。
 *
 * <p>命名规则：{域}_{对象}_{原因}，全大写下划线。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    /** 成功。唯一非业务语义的码值。 */
    public static final String SUCCESS = "0";

    // ===== 通用 =====
    /** 参数校验失败，对应 HTTP 400 */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    /** 资源不存在，对应 HTTP 404 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 未预期的服务端异常，对应 HTTP 500 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // ===== file 域 =====
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    /** mime 不在白名单（jpeg/png/webp/gif），例如收到 image/heic，对应 HTTP 415 */
    public static final String FILE_TYPE_UNSUPPORTED = "FILE_TYPE_UNSUPPORTED";
    /** 超过 multipart 上限，对应 HTTP 413 */
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    /** 写盘 / 硬链接 / 删除失败 */
    public static final String FILE_STORAGE_FAILED = "FILE_STORAGE_FAILED";

    // ===== album 域 =====
    public static final String ALBUM_GROUP_NOT_FOUND = "ALBUM_GROUP_NOT_FOUND";
    public static final String ALBUM_IMAGE_NOT_FOUND = "ALBUM_IMAGE_NOT_FOUND";

    // ===== recipe 域 =====
    public static final String RECIPE_NOT_FOUND = "RECIPE_NOT_FOUND";
    public static final String TAG_NOT_FOUND = "TAG_NOT_FOUND";
    public static final String TAG_NAME_DUPLICATED = "TAG_NAME_DUPLICATED";
    public static final String TYPE_NOT_FOUND = "TYPE_NOT_FOUND";
    public static final String TYPE_NAME_DUPLICATED = "TYPE_NAME_DUPLICATED";

    // ===== vault 域（账号本）=====
    public static final String VAULT_ACCOUNT_NOT_FOUND = "VAULT_ACCOUNT_NOT_FOUND";
}
