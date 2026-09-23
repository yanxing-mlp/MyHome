package com.familyhome.file.biz.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置。
 */
@Component
@ConfigurationProperties(prefix = "fh.storage")
public class FileStorageConfig {

    /** 本地存储根目录，如 /data/family-home/files */
    private String root;

    /** 对外访问前缀，如 /files */
    private String urlPrefix = "/files";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        Path normalized = Path.of(root).toAbsolutePath().normalize();
        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("存储根目录不能是文件系统根目录");
        }
        this.root = normalized.toString();
    }

    /** 路由标记，不是公共存储目录中的子目录。 */
    public static final String PRIVATE_DOCUMENT_PREFIX = "private-documents/";

    /**
     * 私人视频的路由标记，与 {@link #PRIVATE_DOCUMENT_PREFIX} 同一手法：只是一个前缀，
     * 落盘时会被剥掉、真正的字节写进 {@link #getPrivateRoot()} 那个与公共静态根并列的目录。
     * 单独一个前缀（而不是复用 documents 的）是为了让"这一格是视频还是文档"从物理路径就能一眼分辨。
     */
    public static final String PRIVATE_VIDEO_PREFIX = "private-videos/";

    /** 固定派生的同级目录，不提供配置项，避免私人字节落入静态资源根。 */
    public Path getPrivateRoot() {
        Path publicRoot = Path.of(root);
        return publicRoot.resolveSibling(publicRoot.getFileName() + "-private");
    }

    /** 所有读写共用此解析器：拒绝绝对 key、目录穿越及根目录内的符号链接。 */
    public Path resolvePath(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("无效的文件路径");
        }
        // 两类私人前缀都指向同一个私人根；命中哪个就剥掉哪个，剩下的相对路径在私人根下解析。
        String privatePrefix = privatePrefixOf(fileKey);
        Path base = privatePrefix != null ? getPrivateRoot() : Path.of(root);
        String relativeKey = privatePrefix != null ? fileKey.substring(privatePrefix.length()) : fileKey;
        Path relative = Path.of(relativeKey);
        Path target = base.resolve(relative).normalize();
        if (relative.isAbsolute() || !relative.equals(relative.normalize())
                || target.equals(base) || !target.startsWith(base)) {
            throw new IllegalArgumentException("文件路径超出存储范围");
        }
        // 不允许已有符号链接把合法的相对路径带出根，或把私人目录链接回公共目录。
        for (Path current = target; current != null && current.startsWith(base); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("存储路径不能包含符号链接");
            }
        }
        return target;
    }

    /** 命中任一私人前缀就返回它，公共 key 返回 null。新增一类私人内容时在这里加一个前缀即可。 */
    public static String privatePrefixOf(String fileKey) {
        if (fileKey == null) {
            return null;
        }
        if (fileKey.startsWith(PRIVATE_DOCUMENT_PREFIX)) {
            return PRIVATE_DOCUMENT_PREFIX;
        }
        if (fileKey.startsWith(PRIVATE_VIDEO_PREFIX)) {
            return PRIVATE_VIDEO_PREFIX;
        }
        return null;
    }

    public String getUrlPrefix() {
        return urlPrefix;
    }

    public void setUrlPrefix(String urlPrefix) {
        this.urlPrefix = urlPrefix;
    }
}
