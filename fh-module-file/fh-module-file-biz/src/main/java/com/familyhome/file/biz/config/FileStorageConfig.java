package com.familyhome.file.biz.config;

import com.familyhome.file.biz.storage.StorageDirectoryRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
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

    private StorageDirectoryRegistry directoryRegistry;
    private static final String DIRECTORY_PREFIX = "_storage/";

    @Autowired
    public void setDirectoryRegistry(StorageDirectoryRegistry directoryRegistry) {
        this.directoryRegistry = directoryRegistry;
    }

    public boolean isManaged() {
        return directoryRegistry != null;
    }

    /** 旧 key 永远使用首次注册的目录，不能随默认目录或环境变量变化。 */
    public String getRoot() {
        return directoryRegistry == null ? root : directoryRegistry.legacyPath().toString();
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
        Path publicRoot = Path.of(getRoot());
        return publicRoot.resolveSibling(publicRoot.getFileName() + "-private");
    }

    /** 在一次上传开始时固定目录；缩略图沿用同一个 key，切换默认不会拆散一次上传。 */
    public String directoryForUpload(String logicalDirectory) {
        long id = directoryRegistry == null ? 1L : directoryRegistry.defaultIdForUpload();
        if (id == 1L) {
            return logicalDirectory;
        }
        String privatePrefix = privatePrefixOf(logicalDirectory);
        int offset = privatePrefix == null ? 0 : ownerEnd(logicalDirectory, privatePrefix);
        return logicalDirectory.substring(0, offset) + DIRECTORY_PREFIX + id + "/"
                + logicalDirectory.substring(offset);
    }

    /** 所有读写共用：目录标识持久化在 key 中，旧 key 默认 id=1，私人前缀与 owner 保持不变。 */
    public Path resolvePath(String fileKey) {
        validateKey(fileKey);
        String privatePrefix = privatePrefixOf(fileKey);
        int offset = privatePrefix == null ? 0 : ownerEnd(fileKey, privatePrefix);
        long directoryId = 1L;
        String logicalKey = fileKey;
        if (fileKey.startsWith(DIRECTORY_PREFIX, offset)) {
            int idStart = offset + DIRECTORY_PREFIX.length();
            int idEnd = fileKey.indexOf('/', idStart);
            if (idEnd < 0 || !fileKey.substring(idStart, idEnd).matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("无效的存储目录标识");
            }
            directoryId = Long.parseLong(fileKey.substring(idStart, idEnd));
            logicalKey = fileKey.substring(0, offset) + fileKey.substring(idEnd + 1);
        }
        Path base;
        if (directoryRegistry != null) {
            base = directoryRegistry.pathFor(directoryId, privatePrefix != null);
        } else {
            if (directoryId != 1L) {
                throw new IllegalArgumentException("存储目录未注册");
            }
            base = privatePrefix != null ? getPrivateRoot() : Path.of(getRoot());
        }
        String relativeKey = privatePrefix == null ? logicalKey : logicalKey.substring(privatePrefix.length());
        Path relative = Path.of(relativeKey);
        Path target = base.resolve(relative).normalize();
        if (relative.isAbsolute() || !relative.equals(relative.normalize())
                || target.equals(base) || !target.startsWith(base)) {
            throw new IllegalArgumentException("文件路径超出存储范围");
        }
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("存储路径不能包含符号链接");
            }
        }
        return target;
    }

    private static int ownerEnd(String key, String privatePrefix) {
        int end = key.indexOf('/', privatePrefix.length());
        if (end < 0 || !key.substring(privatePrefix.length(), end).matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("无效的私人文件路径");
        }
        return end + 1;
    }

    /** 跨平台一致拒绝反斜线、ADS、空路径段、隐藏文件与穿越；目录标记绝不公开。 */
    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.indexOf('\\') >= 0 || key.indexOf(':') >= 0
                || key.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("无效的文件路径");
        }
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".") || part.endsWith(" ")) {
                throw new IllegalArgumentException("无效的文件路径");
            }
        }
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
