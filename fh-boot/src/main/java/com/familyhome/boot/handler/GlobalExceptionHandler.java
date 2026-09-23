package com.familyhome.boot.handler;

import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常统一转成 {@code Result} + 语义化 HTTP 状态码（方案 §5.2）。
 *
 * <p>状态码映射规则：
 * <ul>
 *   <li>404 —— 码值以 {@code _NOT_FOUND} 结尾；接口路径不存在</li>
 *   <li>405 —— 路径在、方法不对</li>
 *   <li>409 —— 码值以 {@code _DUPLICATED} 结尾</li>
 *   <li>415 —— {@code FILE_TYPE_UNSUPPORTED}</li>
 *   <li>413 —— {@code FILE_TOO_LARGE} / 超出 multipart 上限</li>
 *   <li>400 —— 其余业务异常与参数校验失败</li>
 *   <li>500 —— 未预期异常</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private record DuplicateFailure(String code, String message) {
    }

    private static final Pattern DUPLICATE_KEY = Pattern.compile("for key ['`]([^'`]+)['`]");
    private static final Map<String, DuplicateFailure> DUPLICATE_FAILURES = Map.of(
            "uk_recipe_live_name", new DuplicateFailure(ErrorCode.RECIPE_NAME_DUPLICATED, "已有同名菜品"),
            "uk_recipe_category_name", new DuplicateFailure(ErrorCode.RECIPE_CATEGORY_NAME_DUPLICATED, "已有同名分类"),
            "uk_practice_group_name", new DuplicateFailure(ErrorCode.RECIPE_PRACTICE_GROUP_NAME_DUPLICATED, "已有同名做法分组"),
            "uk_practice_option_name", new DuplicateFailure(ErrorCode.RECIPE_PRACTICE_OPTION_NAME_DUPLICATED, "同一做法分组内选项名称不能重复"),
            "uk_album_group_partition_name", new DuplicateFailure(ErrorCode.ALBUM_GROUP_NAME_DUPLICATED, "当前相册内已有同名分组"),
            "uk_user_live_name", new DuplicateFailure(ErrorCode.USER_NAME_DUPLICATED, "已有同名账号"),
            "uk_user_live_phone", new DuplicateFailure(ErrorCode.USER_PHONE_DUPLICATED, "该手机号已被其他账号使用"),
            "uk_vault_live_entry", new DuplicateFailure(ErrorCode.VAULT_ACCOUNT_DUPLICATED, "密码本中已有相同名称和账号的条目"),
            "uk_file_category_name", new DuplicateFailure(ErrorCode.FILE_CATEGORY_NAME_DUPLICATED, "已有同名文件分类"));

    /** 并发写入最终由数据库裁决；绝不输出包含手机号/账号值及 SQL 的异常原文。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicate(DuplicateKeyException e, HttpServletRequest request) {
        String detail = e.getMostSpecificCause().getMessage();
        Matcher matcher = DUPLICATE_KEY.matcher(detail == null ? "" : detail);
        String key = matcher.find() ? matcher.group(1) : "";
        key = key.substring(key.lastIndexOf('.') + 1);
        DuplicateFailure failure = DUPLICATE_FAILURES.getOrDefault(key,
                new DuplicateFailure(ErrorCode.DATA_DUPLICATED, "数据已存在，请勿重复添加"));
        log.warn("重复写入 {} {} code={}", request.getMethod(), request.getRequestURI(), failure.code());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(failure.code(), failure.message()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return badRequest("请求内容或字段格式不正确");
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常 {} {} code={} msg={}", request.getMethod(), request.getRequestURI(),
                e.getCode(), e.getMessage());
        return ResponseEntity.status(mapStatus(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(msg.isEmpty() ? "参数校验失败" : msg);
    }

    /** 表单/查询参数绑定对象上的校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return badRequest("缺少必填参数: " + e.getParameterName());
    }

    /** 非法 scope/数字等查询参数不是服务端故障；不回显用户提交的原始值。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数格式不正确: " + e.getName());
    }

    /**
     * multipart 表单缺了 {@code @RequestPart} 声明的部分（如上传文档时没带 categoryId）。
     * 抛的是 {@link MissingServletRequestPartException}，与上面的参数缺失不是同一个类，
     * 不单独接住就会被 {@code Exception} 兜底吃成 500——客户端少传字段不该是服务端错误。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return badRequest("缺少必填参数: " + e.getRequestPartName());
    }

    /**
     * 方法参数上的约束校验失败（{@code @Validated} 标在 controller 类上时，
     * {@code @Min}/{@code @Max} 这类注解违反后抛的是 {@link ConstraintViolationException}，
     * 不是 BindException）。不接住会被下面的 {@code Exception} 兜底吃成 500。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超出上限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(ErrorCode.FILE_TOO_LARGE, "文件太大，请压缩后重试"));
    }

    /**
     * 路径能匹配到 {@code @RequestMapping}，但方法不在其中（如对 {@code /album/groups/{id}}
     * 发 GET——那里只剩 PUT/DELETE）。抛的是
     * {@link HttpRequestMethodNotSupportedException}，不单独接住同样会被 {@code Exception}
     * 兜底吃成 500，还会往日志里刷一坨 ERROR 栈——客户端用错方法不该是服务端故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        log.warn("方法不支持 {} {}（支持：{}）", request.getMethod(), request.getRequestURI(),
                String.join("/", e.getSupportedMethods() == null ? new String[0] : e.getSupportedMethods()));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ErrorCode.METHOD_NOT_ALLOWED,
                        request.getMethod() + " 方法不支持: " + request.getRequestURI()));
    }

    /**
     * 未匹配任何 {@code @RequestMapping} 的请求会落到静态资源处理器（本项目确实开着
     * {@code /files/**} 映射，所以它不可能被关掉），Spring Framework 6.1+ 在这里抛的是
     * {@link NoResourceFoundException}，而不是老版本那个 {@code NoHandlerFoundException}
     * ——后者在这个配置下根本不会发生，曾为它写的 404 分支已随本次清理删掉。
     * 不接住这个异常同样会被下面的 {@code Exception} 兜底吃成 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return notFound("接口不存在: " + request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR, "服务器开小差了，请稍后重试"));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.BAD_REQUEST, message));
    }

    private ResponseEntity<Result<Void>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ErrorCode.NOT_FOUND, message));
    }

    private HttpStatus mapStatus(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        // 账号类要在下面两个 endsWith 之前判：USER_NOT_FOUND 按后缀会落到 404，
        // 但"账号被删了"和"没登录"对前端是同一件事（清缓存回登录页），所以都给 401。
        switch (code) {
            case ErrorCode.USER_NOT_LOGIN, ErrorCode.USER_NOT_FOUND -> {
                return HttpStatus.UNAUTHORIZED;
            }
            case ErrorCode.USER_FORBIDDEN -> {
                return HttpStatus.FORBIDDEN;
            }
            default -> {
            }
        }
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.endsWith("_DUPLICATED")) {
            return HttpStatus.CONFLICT;
        }
        return switch (code) {
            case ErrorCode.FILE_TYPE_UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case ErrorCode.FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case ErrorCode.FILE_STORAGE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
