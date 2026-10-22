package com.familyhome.boot.config;

import com.familyhome.boot.service.OperationLogService;
import com.familyhome.common.context.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 操作审计拦截器（方案 v45）：请求完成后组装一条审计记录，交给 {@link OperationLogService} 异步落库。
 *
 * <p><b>注册顺序是关键</b>：本拦截器必须注册在 {@link CurrentUserInterceptor} <b>之后</b>。
 * Spring 的 {@code afterCompletion} 按注册的<b>逆序</b>回调，于是本拦截器的 {@code afterCompletion}
 * 先于 {@code CurrentUserInterceptor} 执行——那一刻 {@code CurrentUserHolder} 里的「当前登录人」<b>还没被 clear</b>，
 * 审计才拿得到操作人。若顺序反了，这里读到的永远是 null。
 *
 * <p><b>为什么用 afterCompletion 而不是 Filter</b>：afterCompletion 对「handler 正常返回」和「抛异常被全局处理器接住」
 * 两种情况都会回调，能覆盖失败请求（如口令错、越权 403）；而它拿到的 response 已是 {@code GlobalExceptionHandler}
 * 写完错误体之后的状态，响应摘要因此能带上业务错误码。
 *
 * <p><b>拿不到 body 的情况</b>：multipart 上传、视频/文档流式响应没有被 {@link OperationLogCachingFilter} 缓存
 * （见该类注释，为防 OOM），这里 {@code instanceof} 判不中就读不到 body——审计照常记，只是入参/响应摘要为空。
 *
 * <p><b>已知边界</b>：若 {@code CurrentUserInterceptor.preHandle} 因令牌非法直接抛 401，本拦截器的 preHandle 尚未执行，
 * afterCompletion 也不会被回调，这类「假令牌」请求不入审计（登录接口本身不带令牌、不受影响，正常记录）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = OperationLogInterceptor.class.getName() + ".START";
    private static final String PAGE_HEADER = "X-Op-Page";

    private final OperationLogService operationLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        try {
            String uri = request.getRequestURI();
            if (!operationLogService.shouldLog(uri)) {
                return;
            }
            Object start = request.getAttribute(START_ATTR);
            long durationMs = start instanceof Long nano ? (System.nanoTime() - nano) / 1_000_000 : 0L;

            String requestBody = cachedBody(request instanceof ContentCachingRequestWrapper cw
                    ? cw.getContentAsByteArray() : null);
            String responseBody = cachedBody(response instanceof ContentCachingResponseWrapper cw
                    ? cw.getContentAsByteArray() : null);

            operationLogService.record(
                    request.getMethod(),
                    uri,
                    request.getQueryString(),
                    requestBody,
                    responseBody,
                    response.getStatus(),
                    durationMs,
                    clientIp(request),
                    request.getHeader(PAGE_HEADER),
                    traceId(request),
                    CurrentUserHolder.get());
        } catch (Exception e) {
            // 审计绝不能影响业务响应；这里吞掉一切异常。
            log.debug("记录操作审计失败（忽略）: {}", e.toString());
        }
    }

    private static String cachedBody(byte[] buf) {
        return buf == null || buf.length == 0 ? null : new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * 取本次请求的 traceId：由 {@link TraceIdFilter} 在进 DispatcherServlet 之前写进 request attribute，
     * 与它塞进 MDC（日志行）和响应头 {@code X-Trace-Id} 的是同一个值——审计行因此能与日志互相对上。
     * 拿不到（理论上不会，除非该请求没经过 TraceIdFilter）就记 null。
     */
    private static String traceId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceIdFilter.ATTR_KEY);
        return attr instanceof String s ? s : null;
    }

    /** 取来源 IP：优先反代头 {@code X-Forwarded-For} 的第一跳，退回直连地址。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
