package com.familyhome.file.api;

import java.io.InputStream;

/**
 * 存储抽象。一期只有 {@code LocalStorageClient}，未来接 OSS 时新增实现。
 *
 * <p><b>不做 {@code fh.storage.type} 配置开关</b>——只有一个实现时那是死代码。
 */
public interface StorageClient {

    /**
     * 写入文件。{@code fileKey} 是相对存储根的路径，如 {@code 2026/09/17/uuid.jpg}。
     */
    void put(String fileKey, InputStream in);

    /**
     * 硬链接：把 {@code existingKey} 指向的物理文件链接到 {@code newKey}。
     * 跨文件系统或目标 fs 不支持时会抛异常，调用方需 fallback 到复制。
     */
    void link(String existingKey, String newKey);

    /**
     * 删除文件（原图 + 缩略图各调一次）。
     */
    void delete(String fileKey);

    /**
     * 检查文件是否存在。
     */
    boolean exists(String fileKey);
}
