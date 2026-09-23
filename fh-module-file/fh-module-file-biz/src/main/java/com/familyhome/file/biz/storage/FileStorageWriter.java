package com.familyhome.file.biz.storage;

import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.DataScope;
import com.familyhome.file.api.StorageClient;
import com.familyhome.file.biz.config.FileStorageConfig;
import com.familyhome.file.biz.dao.FileObjectMapper;
import com.familyhome.file.biz.entity.FileObjectDO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 落盘 + MD5 秒传。图片路径（{@code FileFacadeImpl}）和文档路径（{@code DocumentFileService}）
 * 共用这一份，但内容仅在同一分区内复用：PUBLIC/0，或同账号的 PRIVATE 文档。
 *
 * <p>秒传语义：命中同 md5 的未删除记录时，新建一条 {@code file_object} 记录 +
 * 硬链接复用物理文件，而不是共享同一条记录——删除时各 unlink 各的，互不影响（§6.9）。
 *
 * <p>刻意<b>不管缩略图</b>：那是图片专属的第二份物理文件。命中秒传时这里只负责把源记录的
 * 缩略图一并硬链接过来（没有则 null）；未命中时返回 {@code thumbKey = null}，
 * 由调用方决定要不要生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileStorageWriter {

    private final StorageClient storageClient;
    private final FileObjectMapper fileObjectMapper;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 一次写入的结果。
     */
    @Getter
    @AllArgsConstructor
    public static class Written {

        /** 内容 MD5 */
        private final String md5;

        /** 相对存储根的路径，如 2026/09/18/uuid.csv */
        private final String fileKey;

        /** 秒传命中且源记录有缩略图时为硬链接过来的新缩略图 key，否则 null */
        private final String thumbKey;

        /** true = 命中秒传，物理文件是硬链接 */
        private final boolean duplicated;
    }

    /**
     * 算 md5 → 秒传判定 → 写盘（或硬链接）。
     *
     * @param bytes 文件全部字节
     * @param ext   落盘用的扩展名（不带点），调用方负责它已经洗干净（文档那条路自 2026-09-21 起不限格式，
     *              但只放行 {@code [a-z0-9]}）；空 = 这个文件没有扩展名，key 就整个不带后缀
     */
    public Written write(byte[] bytes, String ext) {
        return write(bytes, ext, new DataPartition(DataScope.PUBLIC, 0L));
    }

    /** 文档专用：私人目录与秒传候选都由服务端构建的分区决定。 */
    public Written writeDocument(byte[] bytes, String ext, DataPartition partition) {
        if (!partition.equals(DataPartition.forRequest(partition.scope()))) {
            throw new IllegalArgumentException("不能写入其他账号的文档分区");
        }
        return write(bytes, ext, partition);
    }

    /**
     * 视频专用：<b>流式</b>写盘，不把整个文件读进内存（视频动辄几百 MB，{@code byte[]} 那条路会撑爆堆）。
     *
     * <p>与文档/图片的差异：① 直接 {@code put(inputStream)}，MD5 用 {@link DigestInputStream} 在这唯一一次
     * 落盘的过程中顺带算出来（不做二次读盘）；② <b>不做秒传</b>——秒传要先知道 MD5 才能命中，而流式写在读完之前
     * 拿不到 MD5，为它多缓一份完整字节就丢了流式的意义，家庭量级也不缺这点空间；③ 私人目录用
     * {@link FileStorageConfig#PRIVATE_VIDEO_PREFIX} 前缀，与文档分开，物理路径一眼能分辨视频还是文档。
     *
     * @param in        视频字节流（调用方负责关闭）
     * @param ext       洗干净的扩展名（{@code [a-z0-9]}），空 = 不带后缀
     * @return md5 在写完后才可得，故 {@link Written#getMd5()} 是这一趟算出的内容 MD5；thumbKey 恒 null、duplicated 恒 false
     */
    public Written writeVideoStream(java.io.InputStream in, String ext, DataPartition partition) {
        if (!partition.equals(DataPartition.forRequest(partition.scope()))) {
            throw new IllegalArgumentException("不能写入其他账号的视频分区");
        }
        String directory = (partition.scope() == DataScope.PRIVATE
                ? FileStorageConfig.PRIVATE_VIDEO_PREFIX + partition.ownerId() + "/" : "videos/")
                + LocalDate.now().format(DATE_PATH);
        String uuid = UUID.randomUUID().toString();
        String fileKey = directory + "/" + uuid + (ext == null || ext.isEmpty() ? "" : "." + ext);

        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
        try {
            storageClient.put(fileKey, new java.security.DigestInputStream(in, digest));
            cleanupAfterRollback(fileKey, null);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return new Written(sb.toString(), fileKey, null, false);
        } catch (RuntimeException e) {
            discard(fileKey, null);
            throw e;
        }
    }

    private Written write(byte[] bytes, String ext, DataPartition partition) {
        String md5 = calculateMd5(bytes);
        String directory = (partition.scope() == DataScope.PRIVATE
                ? FileStorageConfig.PRIVATE_DOCUMENT_PREFIX + partition.ownerId() + "/" : "")
                + LocalDate.now().format(DATE_PATH);
        String uuid = UUID.randomUUID().toString();
        String fileKey = directory + "/" + uuid + (ext == null || ext.isEmpty() ? "" : "." + ext);
        String thumbKey = directory + "/" + uuid + "_t.jpg";

        FileObjectDO existing = partition.scope() == DataScope.PUBLIC
                ? fileObjectMapper.selectByMd5(md5)
                : fileObjectMapper.selectPrivateDocumentByMd5(md5, partition.ownerId());
        if (existing != null && partition.contains(existing.getScope(), existing.getOwnerId())
                && keyMatchesPartition(existing.getFileKey(), partition)) {
            try {
                // 查询后源文件可能已被并发删除；不复用悬空记录，也不让秒传失败阻断正常上传。
                if (storageClient.exists(existing.getFileKey())) {
                    storageClient.link(existing.getFileKey(), fileKey);
                    String linkedThumb = null;
                    if (keyMatchesPartition(existing.getThumbKey(), partition)
                            && storageClient.exists(existing.getThumbKey())) {
                        storageClient.link(existing.getThumbKey(), thumbKey);
                        linkedThumb = thumbKey;
                    }
                    Written written = new Written(md5, fileKey, linkedThumb, true);
                    cleanupAfterRollback(fileKey, linkedThumb);
                    return written;
                }
            } catch (RuntimeException e) {
                log.warn("秒传源不可用，改为写入上传字节: id={}", existing.getId(), e);
                discard(fileKey, thumbKey);
            }
        }

        try {
            storageClient.put(fileKey, new ByteArrayInputStream(bytes));
            cleanupAfterRollback(fileKey, null);
            return new Written(md5, fileKey, null, false);
        } catch (RuntimeException e) {
            discard(fileKey, null);
            throw e;
        }
    }

    private boolean keyMatchesPartition(String key, DataPartition partition) {
        if (key == null) {
            return false;
        }
        return partition.scope() == DataScope.PUBLIC
                ? !key.startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX)
                : key.startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX + partition.ownerId() + "/");
    }

    /** 只清理本次新建的文件/硬链接，不动秒传源；异常不能覆盖原始上传错误。 */
    public void discard(String fileKey, String thumbKey) {
        for (String key : new String[] {fileKey, thumbKey}) {
            if (key != null) {
                try {
                    storageClient.delete(key);
                } catch (RuntimeException e) {
                    log.error("清理未提交的上传文件失败: key={}", key, e);
                }
            }
        }
    }

    /** SQL 插入失败或外层事务回滚时，清理刚刚写出的文件。 */
    public void cleanupAfterRollback(String fileKey, String thumbKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        discard(fileKey, thumbKey);
                    }
                }
            });
        }
    }

    /** 内容 MD5，秒传判定与业务查重都用它 */
    public static String calculateMd5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}
