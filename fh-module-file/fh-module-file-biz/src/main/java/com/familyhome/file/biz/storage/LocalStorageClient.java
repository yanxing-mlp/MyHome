package com.familyhome.file.biz.storage;

import com.familyhome.file.api.StorageClient;
import com.familyhome.file.biz.config.FileStorageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储实现。
 *
 * <p>目录规划：{@code {root}/yyyy/MM/dd/{uuid}.jpg}，按日期分目录避免单目录文件过多。
 */
@Slf4j
@Component
public class LocalStorageClient implements StorageClient {

    private final FileStorageConfig config;

    public LocalStorageClient(FileStorageConfig config) {
        this.config = config;
        // 私人目录与公共静态根并列，不能被 /files/** 映射访问。
        try {
            Path publicRoot = Path.of(config.getRoot());
            Path privateRoot = config.getPrivateRoot();
            if (Files.isSymbolicLink(publicRoot) || Files.isSymbolicLink(privateRoot)) {
                throw new IllegalStateException("存储根目录不能是符号链接");
            }
            Files.createDirectories(publicRoot);
            Files.createDirectories(privateRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存储根目录", e);
        }
    }

    @Override
    public void put(String fileKey, InputStream in) {
        Path target = resolve(fileKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + fileKey, e);
        }
    }

    @Override
    public void link(String existingKey, String newKey) {
        Path existing = resolve(existingKey);
        Path target = resolve(newKey);
        try {
            Files.createDirectories(target.getParent());
            Files.createLink(target, existing);
        } catch (IOException e) {
            log.warn("硬链接失败（{} → {}），fallback 到复制: {}", existingKey, newKey, e.getMessage());
            // fallback 到复制
            try {
                Files.createDirectories(target.getParent());
                Files.copy(existing, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new RuntimeException("复制文件失败: " + newKey, ex);
            }
        }
    }

    @Override
    public void delete(String fileKey) {
        Path target = resolve(fileKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.error("删除文件失败: {}", fileKey, e);
            // 不抛异常，调用方根据日志排查
        }
    }

    @Override
    public boolean exists(String fileKey) {
        return Files.isRegularFile(resolve(fileKey));
    }

    private Path resolve(String fileKey) {
        return config.resolvePath(fileKey);
    }
}
