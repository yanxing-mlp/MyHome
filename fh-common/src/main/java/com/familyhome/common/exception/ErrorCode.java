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
    /** 路径在，方法不对（如对只支持 PUT/DELETE 的 {@code /{id}} 发 GET），对应 HTTP 405 */
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    /** 未预期的服务端异常，对应 HTTP 500 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String DATA_DUPLICATED = "DATA_DUPLICATED";

    // ===== file 域 =====
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    /** 类型不支持：图片 mime 不在白名单（jpeg/png/webp/gif），或文档扩展名不在 csv/md/doc/docx、字节与扩展名不符，对应 HTTP 415 */
    public static final String FILE_TYPE_UNSUPPORTED = "FILE_TYPE_UNSUPPORTED";
    /** 超过 multipart 上限，对应 HTTP 413 */
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    /** 写盘 / 硬链接 / 删除失败 */
    public static final String FILE_STORAGE_FAILED = "FILE_STORAGE_FAILED";
    /** 分类字典不存在，对应 HTTP 404 */
    public static final String FILE_CATEGORY_NOT_FOUND = "FILE_CATEGORY_NOT_FOUND";
    /** 分类重名（uk_name），对应 HTTP 409 */
    public static final String FILE_CATEGORY_NAME_DUPLICATED = "FILE_CATEGORY_NAME_DUPLICATED";

    // ===== album 域 =====
    public static final String ALBUM_GROUP_NOT_FOUND = "ALBUM_GROUP_NOT_FOUND";
    public static final String ALBUM_IMAGE_NOT_FOUND = "ALBUM_IMAGE_NOT_FOUND";
    public static final String ALBUM_GROUP_NAME_DUPLICATED = "ALBUM_GROUP_NAME_DUPLICATED";

    // ===== recipe 域 =====
    public static final String RECIPE_NAME_DUPLICATED = "RECIPE_NAME_DUPLICATED";
    public static final String RECIPE_CATEGORY_NAME_DUPLICATED = "RECIPE_CATEGORY_NAME_DUPLICATED";
    public static final String RECIPE_PRACTICE_GROUP_NAME_DUPLICATED = "RECIPE_PRACTICE_GROUP_NAME_DUPLICATED";
    public static final String RECIPE_PRACTICE_OPTION_NAME_DUPLICATED = "RECIPE_PRACTICE_OPTION_NAME_DUPLICATED";
    public static final String RECIPE_NOT_FOUND = "RECIPE_NOT_FOUND";
    public static final String RECIPE_ORDER_NOT_FOUND = "RECIPE_ORDER_NOT_FOUND";
    public static final String TYPE_NOT_FOUND = "TYPE_NOT_FOUND";
    public static final String TYPE_NAME_DUPLICATED = "TYPE_NAME_DUPLICATED";

    // ===== vault 域（密码本）=====
    public static final String VAULT_ACCOUNT_NOT_FOUND = "VAULT_ACCOUNT_NOT_FOUND";
    public static final String VAULT_ACCOUNT_DUPLICATED = "VAULT_ACCOUNT_DUPLICATED";

    // ===== user 域（账号）=====
    /** 请求头 X-User-Id 缺失或非法，对应 HTTP 401。前端吃到这个码就清本机缓存回登录页 */
    public static final String USER_NOT_LOGIN = "USER_NOT_LOGIN";
    /** 头里的 id 在库里查不到（账号已被删），同样对应 401——与"没登录"走同一个前端分支 */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    /** 权限不够（非管理员调账号管理接口），对应 HTTP 403 */
    public static final String USER_FORBIDDEN = "USER_FORBIDDEN";
    /**
     * 登录或改口令时原口令与所选账号不符，对应 HTTP 400。
     *
     * <p><b>刻意不给 401</b>：两端的 HTTP 层把 401 统一处理成"清掉本机登录态、掉回登录页"
     * （那是给"账号被删了"准备的自愈路径）。口令打错只是一个业务错误，走 401 会让前端把
     * 后端那句"密码与该账号不匹配"吞掉，用户只看到一个莫名其妙的空登录页。
     */
    public static final String USER_PASSWORD_MISMATCH = "USER_PASSWORD_MISMATCH";
    /** 昵称或手机号与存活账号重复（应用层预检 + V503 唯一索引），对应 HTTP 409 */
    public static final String USER_NAME_DUPLICATED = "USER_NAME_DUPLICATED";
    public static final String USER_PHONE_DUPLICATED = "USER_PHONE_DUPLICATED";
}
