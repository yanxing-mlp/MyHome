package com.familyhome.file.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.FileUploadRequest;
import com.familyhome.file.api.StorageClient;
import com.familyhome.file.api.dto.FileDTO;
import com.familyhome.file.biz.config.FileStorageConfig;
import com.familyhome.file.biz.dao.FileObjectMapper;
import com.familyhome.file.biz.entity.FileObjectDO;
import com.familyhome.file.biz.storage.FileStorageWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文件域门面实现。
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #upload(FileUploadRequest)} — 上传、MD5 秒传、缩略图生成、落库</li>
 *   <li>{@link #mapByIds(List)} — 批量 ID → URL 映射</li>
 *   <li>{@link #lockByIds(List)} — 调用方事务内按 ID 升序锁文件行</li>
 *   <li>{@link #markDeletedAndPurge(List)} — 先 DB 软删再物理删文件（顺序不能反）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileFacadeImpl implements FileFacade {

    private final StorageClient storageClient;
    private final FileObjectMapper fileObjectMapper;
    private final FileStorageConfig config;
    private final FileStorageWriter storageWriter;

    /** MIME 白名单：只收这四种（HEIC 一律在前端转 JPEG，见 §6.6 坑 1）*/
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    @Override
    public FileDTO upload(FileUploadRequest request) {
        Long creatorId = CurrentUserHolder.requireUserId();
        if (DocumentFileService.BIZ_TYPE_DOCUMENT.equalsIgnoreCase(
                request.getBizType() == null ? null : request.getBizType().trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文档请通过文件管理入口上传");
        }
        // 1. mime 白名单校验
        if (!ALLOWED_MIME_TYPES.contains(request.getMimeType())) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "不支持的文件类型: " + request.getMimeType());
        }

        // 2. 读取全部字节（家庭场景单图几 MB，内存缓存可接受）
        byte[] fileBytes;
        try (InputStream in = request.getInputStream()) {
            fileBytes = readAllBytes(in);
        } catch (IOException e) {
            log.error("读取文件失败", e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }

        // 3. 算 md5 + 秒传判定 + 写盘（或硬链接）。这段与文档上传共用 FileStorageWriter。
        String ext = extractExt(request.getOriginName());
        FileStorageWriter.Written written = storageWriter.write(fileBytes, ext);

        // 4. 缩略图：秒传时 writer 已经把源记录的缩略图硬链接过来，否则现生成
        String fileKey = written.getFileKey();
        String thumbKey = written.getThumbKey();
        if (thumbKey == null) {
            try {
                thumbKey = generateThumbnail(fileBytes, fileKey);
            } catch (Exception e) {
                log.warn("缩略图生成失败，继续执行: {}", e.getMessage());
            }
        }

        // 5. 读宽高（ImageIO 从内存字节数组读，避免再次读盘）
        Integer width = null;
        Integer height = null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException e) {
            log.warn("读取图片宽高失败: {}", e.getMessage());
        }

        // 6. 落 file_object
        FileObjectDO fileObject = new FileObjectDO();
        fileObject.setFileKey(fileKey);
        fileObject.setThumbKey(thumbKey);
        fileObject.setOriginName(request.getOriginName());
        fileObject.setMd5(written.getMd5());
        fileObject.setMimeType(request.getMimeType());
        fileObject.setExt(ext);
        fileObject.setFileSize((long) fileBytes.length);
        fileObject.setWidth(width);
        fileObject.setHeight(height);
        fileObject.setHardLink(written.isDuplicated() ? 1 : 0);
        fileObject.setBizType(request.getBizType());
        // 仅文档使用分区权限；图片固定 PUBLIC/0，相册/菜谱的权限仍由其业务域决定。
        fileObject.setScope(DataScope.PUBLIC);
        fileObject.setOwnerId(0L);
        fileObject.setCreatorId(creatorId);
        try {
            fileObjectMapper.insert(fileObject);

            log.info("文件上传完成: id={}, fileKey={}, size={}, md5={}, duplicated={}",
                    fileObject.getId(), fileKey, fileObject.getFileSize(), written.getMd5(), written.isDuplicated());
            return toDTO(fileObject);
        } catch (RuntimeException e) {
            storageWriter.discard(fileKey, thumbKey);
            throw e;
        }
    }

    @Override
    public Map<Long, FileDTO> mapByIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 文档不能作为相册/菜谱/头像的图片绑定，PUBLIC 文档也必须排除。
        List<FileObjectDO> files = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObjectDO>()
                .in(FileObjectDO::getId, fileIds)
                .ne(FileObjectDO::getBizType, DocumentFileService.BIZ_TYPE_DOCUMENT)
                .eq(FileObjectDO::getScope, DataScope.PUBLIC)
                .eq(FileObjectDO::getOwnerId, 0L));
        Map<Long, FileDTO> result = new HashMap<>(files.size());
        for (FileObjectDO file : files) {
            result.put(file.getId(), toDTO(file));
        }
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockByIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        fileObjectMapper.lockByIds(fileIds.stream().distinct().sorted().toList());
    }

    @Override
    @Transactional
    public void markDeletedAndPurge(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }

        // 1. 事务内：标记 deleted = 1
        //    必须走 deleteById（@TableLogic 会让它变成 UPDATE ... SET deleted=1 WHERE id=? AND deleted=0），
        //    不能 setDeleted(1) + updateById：MyBatis-Plus 把逻辑删除字段从 update 的 SET 列表里排除了，
        //    那样标记会静默丢失，而提交后物理文件照删 —— 记录还在、字节没了，链接全成 404。
        List<FileObjectDO> files = fileObjectMapper.selectBatchIds(fileIds);
        for (FileObjectDO file : files) {
            fileObjectMapper.deleteById(file.getId());
        }

        // 2. 注册事务提交后的回调，确保"先 DB 后文件"的顺序（§6.7）
        // 如果当前不在事务中（比如单元测试直接调），TransactionSynchronizationManager 不会激活，
        // 这时退化到同步执行 —— 功能正确性不受影响，只是少了"事务回滚时不删文件"的保护。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    purgePhysicalFiles(files);
                }
            });
        } else {
            // 无事务上下文，直接删（降级路径）
            log.warn("markDeletedAndPurge 在无事务上下文中调用，退化到同步删除");
            purgePhysicalFiles(files);
        }
    }

    /**
     * 物理删除文件列表。失败只记 ERROR 日志，不回滚。
     */
    private void purgePhysicalFiles(List<FileObjectDO> files) {
        for (FileObjectDO file : files) {
            try {
                storageClient.delete(file.getFileKey());
                if (file.getThumbKey() != null) {
                    storageClient.delete(file.getThumbKey());
                }
            } catch (Exception e) {
                log.error("物理删除文件失败: fileKey={}, thumbKey={}",
                        file.getFileKey(), file.getThumbKey(), e);
                // 不抛异常，不回滚 —— 最坏情况是磁盘浪费，可被清理任务兜住
            }
        }
    }

    // ========== 私有方法 ==========

    private FileDTO toDTO(FileObjectDO file) {
        if (file.getScope() != DataScope.PUBLIC || !Long.valueOf(0L).equals(file.getOwnerId())
                || DocumentFileService.BIZ_TYPE_DOCUMENT.equalsIgnoreCase(file.getBizType())
                || file.getFileKey().startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX)
                || file.getThumbKey() != null
                && file.getThumbKey().startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "文件不存在或已被删除");
        }
        FileDTO dto = new FileDTO();
        dto.setId(file.getId());
        dto.setCreatorId(file.getCreatorId());
        dto.setUrl(config.getUrlPrefix() + "/" + file.getFileKey());
        dto.setThumbUrl(file.getThumbKey() != null
                ? config.getUrlPrefix() + "/" + file.getThumbKey()
                : null);
        dto.setOriginName(file.getOriginName());
        dto.setMd5(file.getMd5());
        dto.setMimeType(file.getMimeType());
        dto.setExt(file.getExt());
        dto.setFileSize(file.getFileSize());
        dto.setWidth(file.getWidth());
        dto.setHeight(file.getHeight());
        dto.setBizType(file.getBizType());
        return dto;
    }

    /**
     * 生成缩略图：长边压到 480px，JPEG 质量 0.8。
     *
     * <p>不需要旋转处理——前端 canvas 转码时已经应用了 EXIF Orientation（§6.6）。
     *
     * @param fileKey 原图的相对路径，缩略图在它基础上加 {@code _t.jpg} 后缀
     * @return 缩略图的 fileKey，生成失败返回 null
     */
    private String generateThumbnail(byte[] sourceBytes, String fileKey) throws IOException {
        int dot = fileKey.lastIndexOf('.');
        String thumbKey = (dot < 0 ? fileKey : fileKey.substring(0, dot)) + "_t.jpg";
        Path thumbPath = config.resolvePath(thumbKey);
        try {
            Files.createDirectories(thumbPath.getParent());
            Thumbnails.of(new ByteArrayInputStream(sourceBytes))
                    .size(480, 480)  // 长边 ≤480，保持纵横比
                    .outputQuality(0.8)
                    .outputFormat("jpg")
                    .toFile(thumbPath.toFile());
            storageWriter.cleanupAfterRollback(null, thumbKey);
            return thumbKey;
        } catch (IOException | RuntimeException e) {
            storageWriter.discard(null, thumbKey);
            throw e;
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }

    private String extractExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "jpg";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
