package com.familyhome.boot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 操作审计的「缓存包装」过滤器（方案 v45）。
 *
 * <p>审计要在请求完成后读到<b>入参 body</b> 和<b>响应 body</b>，但 Servlet 的流只能读一次——业务读完就没了。
 * 这一层用 Spring 的 {@link ContentCachingRequestWrapper} / {@link ContentCachingResponseWrapper} 把两者各存一份副本，
 * 供随后执行的 {@link OperationLogInterceptor} 在 {@code afterCompletion} 里读取；响应副本读完后必须
 * {@code copyBodyToResponse()} 把缓存写回真实响应，否则客户端收不到 body。
 *
 * <p>执行时序：本过滤器包住 {@code DispatcherServlet}，所以 {@code afterCompletion}（拦截器）先跑、
 * {@code finally} 里的 {@code copyBodyToResponse()} 后跑——拦截器读缓存时内容还在，客户端也照常拿到完整响应。
 *
 * <p><b>两种请求刻意不缓存 body</b>：
 * <ul>
 *   <li><b>multipart 上传</b>：文件动辄几百 MB，缓存进内存会 OOM；这类请求仍记审计，只是入参不含文件字节
 *       （前端也不会把文件塞进 JSON body）。</li>
 *   <li><b>流式响应</b>（视频 {@code /stream}、文档 {@code /download}）：整段二进制缓存进内存同样会 OOM，
 *       且 {@code copyBodyToResponse} 会破坏 Range 续传；这类请求仍记审计，只是响应摘要为空。</li>
 * </ul>
 *
 * <p>只作用于 {@code /api/**}：静态资源 {@code /files/**} 不进审计，也不该被无谓地包一层。
 */
@Component
public class OperationLogCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        boolean streaming = uri.contains("/stream") || uri.contains("/download");

        HttpServletRequest requestToUse = multipart ? request : new ContentCachingRequestWrapper(request);
        HttpServletResponse responseToUse = streaming ? response : new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(requestToUse, responseToUse);
        } finally {
            if (responseToUse instanceof ContentCachingResponseWrapper caching) {
                caching.copyBodyToResponse();
            }
        }
    }
}
