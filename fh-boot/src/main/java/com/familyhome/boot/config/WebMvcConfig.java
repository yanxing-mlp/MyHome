package com.familyhome.boot.config;

import com.familyhome.file.biz.config.FileStorageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 全局 Web 配置。
 *
 * <p>不配 CORS：开发期 Vite dev server 走 proxy 是同源请求，生产由 nginx 同域反代，
 * 两种情况都不会触发跨域。真需要直连 8080 调试时再临时加。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 认"现在是谁"的那一层，见 {@link CurrentUserInterceptor}；它自己带上了所需的服务。 */
    private final CurrentUserInterceptor currentUserInterceptor;

    /** 操作审计层，见 {@link OperationLogInterceptor}；<b>必须注册在 currentUserInterceptor 之后</b>。 */
    private final OperationLogInterceptor operationLogInterceptor;

    private final FileStorageConfig storageConfig;

    /**
     * 单端口托管前端（Topology A）：部署机不再跑 Vite dev server，改由本进程把两份**已构建**的前端产物
     * 当静态资源发出去，前后端同源，不再需要任何反向代理。两个目录都由部署脚本注入环境变量：
     * <ul>
     *   <li>{@code fh.web.admin-dir} → B 端 admin 的 {@code dist}，挂在 {@code /admin/**}（vite base='/admin/'）；</li>
     *   <li>{@code fh.web.h5-dir} → C 端 h5 的 {@code dist}，挂在 {@code /**}（vite base='/'）。</li>
     * </ul>
     * <b>留空即不启用</b>：开发机两个前端各跑各的 Vite dev server（5173/5174），后端只出 /api 与 /files，
     * 不注册这两个 SPA 处理器，行为与改造前完全一致。
     */
    @Value("${fh.web.admin-dir:}")
    private String adminDir;

    @Value("${fh.web.h5-dir:}")
    private String h5Dir;

    /**
     * 统一按持久化目录标识路由；nginx 必须反代 /files，不能 alias 到单一默认目录。
     * 文件名带 UUID，仍沿用一年 immutable 缓存，旧 URL 不变。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(storageConfig.getRoot()).toUri().toString();
        if (!location.endsWith("/")) location += "/";
        String pattern = storageConfig.getUrlPrefix() + "/**";

        registry.addResourceHandler(pattern)
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 私人逻辑路径（文档 private-documents/、视频 private-videos/）永不作为静态资源；
                        // 即便误放进公共根也不可访问。命中任一私人前缀就当作不存在。
                        if (FileStorageConfig.privatePrefixOf(resourcePath) != null) {
                            return null;
                        }
                        try {
                            Path path = storageConfig.resolvePath(resourcePath);
                            return Files.isRegularFile(path) && Files.isReadable(path)
                                    ? new FileSystemResource(path) : null;
                        } catch (IllegalArgumentException | SecurityException e) {
                            return null;
                        }
                    }
                });

        log.info("静态资源映射: {} -> 已注册存储目录", pattern);

        // ---- 单端口托管两份前端产物（仅当部署脚本注入了目录才启用）----
        // 顺序无所谓：Spring 按 Ant 模式的具体程度排序，/admin/** 比 /** 更具体，先命中；
        // /api/** 是 Controller（RequestMappingHandlerMapping 优先级高于资源处理器），永不被 /** 抢走。
        if (isUsableDir(adminDir)) {
            String loc = Path.of(adminDir).toUri().toString();
            registry.addResourceHandler("/admin/**")
                    .addResourceLocations(loc)
                    // index.html 不缓存，保证「部署最新」换包后刷新即得新版；带哈希的 assets 可长缓存。
                    .setCacheControl(CacheControl.noCache())
                    .resourceChain(false)
                    .addResolver(new SpaFallbackResolver(List.of()));
            log.info("B 端前端托管: /admin/** -> {}", adminDir);
        }
        if (isUsableDir(h5Dir)) {
            String loc = Path.of(h5Dir).toUri().toString();
            // excludes 是纵深防御：/api、/files、/admin 各有更具体的处理器，正常轮不到 /**，
            // 但万一有未映射的 /api/xxx 落到这里，也必须 404 成 JSON 语义（返回 null），而不是回一张 h5 index.html。
            registry.addResourceHandler("/**")
                    .addResourceLocations(loc)
                    .setCacheControl(CacheControl.noCache())
                    .resourceChain(false)
                    .addResolver(new SpaFallbackResolver(List.of("api", "files", "admin", "actuator")));
            log.info("C 端前端托管: /** -> {}", h5Dir);
        }
    }

    private static boolean isUsableDir(String dir) {
        if (dir == null || dir.isBlank()) return false;
        Path p = Path.of(dir.trim());
        return Files.isDirectory(p) && Files.isRegularFile(p.resolve("index.html"));
    }

    /**
     * 两个 SPA 的「根」入口用 forward 兜住。
     *
     * <p>为什么不能只靠上面的资源处理器：Spring 的 {@code ResourceHttpRequestHandler} 对
     * <b>空路径</b>（{@code /} 命中 {@code /**}、{@code /admin/} 命中 {@code /admin/**} 后剩余部分为空）
     * 会直接返回 null，<b>根本不进解析器链</b>——所以 {@code SpaFallbackResolver} 里那段「resourcePath 为空给 index.html」
     * 对根路径是死代码，深链（{@code /admin/album}）能命中而根 404。这里用 ViewController 把根 forward 到各自的
     * {@code index.html}（再走一遍资源处理器，这次路径非空就能命中真实文件）。
     *
     * <p>ViewController 的 handlerMapping order=1，早于资源处理器（order 最低）、晚于 {@code @RequestMapping}
     * 控制器（order=0），所以 {@code /api/**} 与 {@code /files/**} 都不受影响。裸 {@code /admin}（无尾斜杠）
     * 重定向到 {@code /admin/}，与前端 basename 口径一致（见 admin router.tsx 注释）。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (isUsableDir(adminDir)) {
            registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
            registry.addRedirectViewController("/admin", "/admin/");
        }
        if (isUsableDir(h5Dir)) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    /**
     * 单页应用（BrowserRouter）回退解析器：请求的真实文件（assets、index.html）命中就原样返回；
     * 命中不到时，只要这是一个「前端路由」（末段无扩展名、且不在排除前缀里），就回退到 index.html，
     * 交给浏览器里的 React Router 处理；否则返回 null（404），绝不把缺失的 .js/.png 之类糊成一张 HTML。
     */
    private static final class SpaFallbackResolver extends PathResourceResolver {
        private final List<String> excludes;

        SpaFallbackResolver(List<String> excludes) {
            this.excludes = excludes;
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // 兜底：空 resourcePath 理论上到不了这里（根路径由 addViewControllers 的 forward 处理，
            // ResourceHttpRequestHandler 对空路径直接返回 null），但保留一行防御，真要进来也给 index.html。
            if (resourcePath.isEmpty()) {
                return readable(location.createRelative("index.html"));
            }
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            for (String ex : excludes) {
                if (resourcePath.equals(ex) || resourcePath.startsWith(ex + "/")) {
                    return null;
                }
            }
            String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (lastSegment.contains(".")) {
                // 带扩展名却找不到 = 真的缺文件，404；不拿 index.html 冒充。
                return null;
            }
            return readable(location.createRelative("index.html"));
        }

        private static Resource readable(Resource r) {
            try {
                return r.exists() && r.isReadable() ? r : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 注册 {@link CurrentUserInterceptor}：认"现在是谁"，<b>不做</b>访问控制。
     *
     * <p>这一层只解析 {@code Authorization: Bearer <令牌>}、验签后写进 ThreadLocal，头缺失也放行（当匿名），
     * 所以没有白名单要维护（{@code /login} 和 {@code /options} 天生不带这个头）。"写数据必须有身份"由各 service 调
     * {@code CurrentUserHolder.requireUserId()} 保证；"谁能管账号"由 {@code requireAdmin()} 保证；
     * B 端接口整体暴露到什么网络，仍然是部署层的 nginx 内网白名单（方案 §8.3）。
     *
     * <p>只拦 {@code /api/**}：{@code /files/**} 走静态资源映射，图片本身要能直接放进
     * {@code <img src>}，不可能带自定义头。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(currentUserInterceptor)
                .addPathPatterns("/api/**");
        // 顺序不可颠倒：afterCompletion 逆序回调，审计层注册在后 → 它的 afterCompletion 先跑，
        // 那时 CurrentUserHolder 还没被 currentUserInterceptor 清掉，审计才拿得到操作人（见 OperationLogInterceptor 注释）。
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/**");
    }
}
