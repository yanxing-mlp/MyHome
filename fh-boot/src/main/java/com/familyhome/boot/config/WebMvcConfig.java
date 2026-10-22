package com.familyhome.boot.config;

import com.familyhome.file.biz.config.FileStorageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
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
