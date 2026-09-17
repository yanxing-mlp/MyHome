package com.familyhome.boot.config;

import java.nio.file.Paths;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 Web 配置。
 *
 * <p>不配 CORS：开发期 Vite dev server 走 proxy 是同源请求，生产由 nginx 同域反代，
 * 两种情况都不会触发跨域。真需要直连 8080 调试时再临时加。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

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
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        log.info("静态资源映射: {} -> {}", pattern, location);
    }

    /**
     * 一期不做鉴权，此处<b>刻意留空</b>。
     *
     * <p>将来加 token 校验只在这一处插入，不动任何业务代码（方案 §5.7）。
     * B 端接口的访问控制目前完全依赖 nginx 的内网网段白名单（方案 §8.3）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // intentionally empty
    }
}
