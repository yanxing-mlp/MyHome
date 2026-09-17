package com.familyhome.boot.handler;

import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常统一转成 {@code Result} + 语义化 HTTP 状态码（方案 §5.2）。
 *
 * <p>状态码映射规则：
 * <ul>
 *   <li>404 —— 码值以 {@code _NOT_FOUND} 结尾</li>
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

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException e) {
        return notFound("接口不存在: " + e.getRequestURL());
    }

    /**
     * 未匹配任何 {@code @RequestMapping} 的请求会落到静态资源处理器，
     * Spring Framework 6.1+ 在这里抛 {@link NoResourceFoundException} 而不是
     * {@link NoHandlerFoundException}。不单独接住就会被下面的 {@code Exception} 兜底吃成 500。
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
