package com.familyhome.boot.config;

import com.familyhome.file.biz.config.FileStorageConfig;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${fh.storage.root}")
    private String storageRoot;

    @Value("${fh.storage.url-prefix}")
    private String urlPrefix;

    /**
     * 把 {@code /files/**} 映射到本地存储根目录。
     *
     * <p>生产环境 nginx 会直接 {@code alias} 读盘绕过 Java 进程，两者路径规则一致，切换无感（方案 §6.4）。
     *
     * <p>缓存策略设为一年 + immutable：文件名带 UUID，内容永不变，可以放心永久缓存。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absoluteRoot = Paths.get(storageRoot).toAbsolutePath().normalize().toString();
        String location = "file:" + absoluteRoot + "/";
        String pattern = urlPrefix + "/**";

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
                        return super.getResource(resourcePath, location);
                    }
                });

        log.info("静态资源映射: {} -> {}", pattern, location);
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
    }
}
