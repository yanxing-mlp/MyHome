package com.familyhome.boot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路 traceId 过滤器（方案 v46）：为每个 {@code /api/**} 请求生成一个短 traceId，
 * 让它同时出现在<b>日志</b>、<b>操作审计行</b>和<b>响应头</b>三处，从而把「一次操作」与「它产生的日志」串起来。
 *
 * <p><b>为什么要一个独立 Filter 而不是在拦截器里生成</b>：MDC 是线程本地变量，业务代码在整个请求处理期间打的日志
 * 都要带上 traceId，所以必须在<b>进入 DispatcherServlet 之前</b>就 {@code MDC.put}、并在请求结束的 {@code finally} 里
 * {@code MDC.remove}（线程池复用线程，不清会串到下一个请求）。Filter 天然包住整条链路，是最早也最完整的挂载点。
 * 用 {@link Ordered#HIGHEST_PRECEDENCE} 保证它跑在 {@link OperationLogCachingFilter} 之前——那样连缓存包装层的日志也带 trace。
 *
 * <p><b>三处落点，一份 traceId</b>：
 * <ul>
 *   <li><b>日志</b>：{@code MDC.put("traceId", ...)}，配合 {@code application.yml} 的
 *       {@code logging.pattern.level=%5p [%X{traceId:-}]}，该请求期间的所有日志行都会带上 {@code [traceId]}。
 *       非请求线程（启动、{@code @Scheduled} 清理）MDC 里没有它，模式里的 {@code :-} 兜底成空，日志照常打、只是方括号内为空。</li>
 *   <li><b>审计</b>：存进 request attribute（{@link #ATTR_KEY}），{@link OperationLogInterceptor} 在 afterCompletion 里
 *       读出同一个值写进 {@code operation_log.trace_id}——审计行与日志因此可互相对上。</li>
 *   <li><b>响应头</b>：回写 {@link #HEADER}，便于前端/排障时从一次失败的响应直接拿到 traceId。</li>
 * </ul>
 *
 * <p><b>traceId 只在服务端生成</b>，不信任也不透传入站头：本项目是单体、无跨服务调用，不需要分布式追踪的传播链，
 * 一个请求一个新 id 最简单也最不会串。16 位十六进制（64 bit）对一个家庭应用的请求量足够不碰撞，且短、日志里好读。
 *
 * <p>只作用于 {@code /api/**}：静态资源 {@code /files/**} 不产生业务日志、也不进审计，无需为它生成 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键，与 {@code logging.pattern.level} 里的 {@code %X{traceId}} 对应。 */
    public static final String MDC_KEY = "traceId";

    /** request attribute 键，供 {@link OperationLogInterceptor} 读出同一个 traceId 落进审计行。 */
    public static final String ATTR_KEY = TraceIdFilter.class.getName() + ".TRACE_ID";

    /** 回写给客户端的响应头，便于从一次响应直接拿到 traceId。 */
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = newTraceId();
        MDC.put(MDC_KEY, traceId);
        request.setAttribute(ATTR_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被容器复用，务必清掉，否则 traceId 会串到复用同一线程的下一个请求。
            MDC.remove(MDC_KEY);
        }
    }

    /** 16 位十六进制短 id：够不碰撞、又比整条 UUID 短，日志里好读、页面上好复制。 */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
