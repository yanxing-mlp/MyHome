package com.familyhome.file.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.biz.config.FileStorageConfig;
import com.familyhome.file.biz.dao.FileObjectMapper;
import com.familyhome.file.biz.entity.FileObjectDO;
import com.familyhome.file.biz.model.bo.DocumentType;
import com.familyhome.file.biz.model.vo.admin.DocumentFileVO;
import com.familyhome.file.biz.storage.FileStorageWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档文件服务（B 端"文件管理"页）。
 *
 * <p><b>为什么不另建业务表</b>：文档的元数据（名字、大小、md5、物理路径）{@code file_object}
 * 全都有，唯一多出来的业务属性是"分类"，就用一个可空列 {@code category_id} 承载，
 * {@code biz_type = document} 与图片行区分。相册/菜谱那种"有自己的业务语义"才配独立表。
 *
 * <p><b>与图片上传的差异</b>：不走 {@code FileFacade.upload}（那条路是图片专属的
 * mime 白名单 + 缩略图 + 宽高），但共用 {@link FileStorageWriter} 的 md5 秒传与写盘，
 * 删除也复用 {@link FileFacade#markDeletedAndPurge}，"先软删记录、提交后删物理文件"的顺序只有一处实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentFileService {

    /** file_object.biz_type 的文档取值（图片行是 ALBUM_IMAGE / RECIPE_IMAGE）；本常量是唯一的等值判断口径 */
    public static final String BIZ_TYPE_DOCUMENT = "document";

    private final FileObjectMapper fileObjectMapper;
    private final FileCategoryService categoryService;
    private final FileStorageWriter storageWriter;
    private final FileFacade fileFacade;
    private final FileStorageConfig config;

    /**
     * 列表：最近上传的在前，不分页（家庭量级，一期就几十份）。
     *
     * @param categoryId null = 全部分类
     */
    public List<DocumentFileVO> list(Long categoryId, DataScope scope) {
        DataPartition partition = DataPartition.forRequest(scope);
        LambdaQueryWrapper<FileObjectDO> wrapper = documentQuery(partition);
        if (categoryId != null) {
            categoryService.require(categoryId, partition);
            wrapper.eq(FileObjectDO::getCategoryId, categoryId);
        }
        wrapper.orderByDesc(FileObjectDO::getId);

        List<FileObjectDO> rows = fileObjectMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = categoryService.nameMap(partition);
        return rows.stream().map(row -> toVO(row, categoryNames)).toList();
    }

    /**
     * 上传一份文档。
     *
     * <p>顺序刻意是"先校验分类 → 再解析类型 → 才写盘"：分类不存在时必须一分磁盘都不碰。
     * 类型解析（{@link DocumentType}）2026-09-21 起不再限格式、也就不会失败，只剩"洗扩展名 + 查 mime"。
     *
     * @param file       文件本体
     * @param categoryId 分类，必选
     */
    @Transactional
    public DocumentFileVO upload(MultipartFile file, Long categoryId, DataScope scope) {
        DataPartition partition = DataPartition.forRequest(scope);
        categoryService.require(categoryId, partition);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败: name={}", file.getOriginalFilename(), e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }

        String originName = file.getOriginalFilename();
        DocumentType type = DocumentType.resolve(originName);
        FileStorageWriter.Written written = storageWriter.writeDocument(bytes, type.ext(), partition);

        FileObjectDO record = new FileObjectDO();
        record.setFileKey(written.getFileKey());
        // 文档自己不需要缩略图；秒传命中一条图片记录时会带回硬链接的缩略图，存下来是为了删除时能一起清掉
        record.setThumbKey(written.getThumbKey());
        record.setOriginName(originName);
        record.setMd5(written.getMd5());
        // 落解析出的规范 mime，浏览器给的 Content-Type 一律不信
        record.setMimeType(type.mimeType());
        record.setExt(type.ext());
        record.setFileSize((long) bytes.length);
        record.setHardLink(written.isDuplicated() ? 1 : 0);
        record.setBizType(BIZ_TYPE_DOCUMENT);
        record.setCategoryId(categoryId);
        record.setScope(partition.scope());
        record.setOwnerId(partition.ownerId());
        record.setCreatorId(CurrentUserHolder.requireUserId());
        try {
            fileObjectMapper.insert(record);

            log.info("文档上传完成: id={}, name={}, ext={}, size={}, categoryId={}, duplicated={}",
                    record.getId(), originName, type.ext(), bytes.length, categoryId, written.isDuplicated());
            // DB 默认时间需要回读；仍按当前文档分区查询。
            return toVO(requireDocument(record.getId(), partition), categoryService.nameMap(partition));
        } catch (RuntimeException e) {
            storageWriter.discard(written.getFileKey(), written.getThumbKey());
            throw e;
        }
    }

    /**
     * 删除：软删记录 + 提交后物理删文件（口径与图片一致）。
     *
     * <p>只认 document 行——防止有人拿这个入口把相册图片的物理文件删掉。
     */
    @Transactional
    public void delete(Long id, DataScope scope) {
        FileObjectDO record = requireDocument(id, DataPartition.forRequest(scope));
        fileFacade.markDeletedAndPurge(List.of(id));
        log.info("删除文档文件: id={}, name={}", id, record.getOriginName());
    }

    /** 私人文件不暴露静态地址；下载公共文件同样要求登录并校验请求分区。 */
    public ResponseEntity<Resource> download(Long id, DataScope scope) {
        DataPartition partition = DataPartition.forRequest(scope);
        FileObjectDO record = requireDocument(id, partition);
        String disposition = ContentDisposition.attachment()
                .filename(downloadName(record.getOriginName()), StandardCharsets.UTF_8).build().toString();
        try {
            String key = record.getFileKey();
            // 防止错误元数据把 PUBLIC 行指向私人路径，或把私人行指向另一账号目录。
            if (key == null || (partition.scope() == DataScope.PRIVATE
                    ? !key.startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX + partition.ownerId() + "/")
                    : key.startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX))) {
                throw fileNotFound();
            }
            Path path = config.resolvePath(key);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw fileNotFound();
            }
            long size = Files.size(path);
            // 返回前打开文件，将不存在/无权限/并发删除统一成 404，而非在响应阶段泄漏物理路径。
            Resource resource = new InputStreamResource(Files.newInputStream(path));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(resource);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            throw fileNotFound();
        }
    }

    private LambdaQueryWrapper<FileObjectDO> documentQuery(DataPartition partition) {
        return new LambdaQueryWrapper<FileObjectDO>()
                .eq(FileObjectDO::getBizType, BIZ_TYPE_DOCUMENT)
                .eq(FileObjectDO::getScope, partition.scope())
                .eq(FileObjectDO::getOwnerId, partition.ownerId())
                .eq(FileObjectDO::getDeleted, 0);
    }

    private FileObjectDO requireDocument(Long id, DataPartition partition) {
        FileObjectDO record = id == null ? null : fileObjectMapper.selectOne(
                documentQuery(partition).eq(FileObjectDO::getId, id));
        if (record == null || !BIZ_TYPE_DOCUMENT.equals(record.getBizType())
                || !partition.contains(record.getScope(), record.getOwnerId())
                || Integer.valueOf(1).equals(record.getDeleted())) {
            throw fileNotFound();
        }
        return record;
    }

    private BizException fileNotFound() {
        return BizException.of(ErrorCode.FILE_NOT_FOUND, "文件不存在或已被删除");
    }

    private String downloadName(String originName) {
        if (originName == null) {
            return "download";
        }
        // 去掉客户端路径和所有控制字符，UTF-8 编码及引号转义由 ContentDisposition 负责。
        String name = originName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        return name.isBlank() || ".".equals(name) || "..".equals(name) ? "download" : name;
    }

    private DocumentFileVO toVO(FileObjectDO record, Map<Long, String> categoryNames) {
        DocumentFileVO vo = new DocumentFileVO();
        vo.setId(record.getId());
        vo.setName(record.getOriginName());
        vo.setCategoryId(record.getCategoryId());
        vo.setCategoryName(record.getCategoryId() == null
                ? null : categoryNames.get(record.getCategoryId()));
        // 类型列就是扩展名大写，不另存一列；没扩展名的文件这里是空串，前端那一格留空
        vo.setFileType(record.getExt() == null ? null : record.getExt().toUpperCase(Locale.ROOT));
        vo.setFileSize(record.getFileSize());
        vo.setScope(record.getScope());
        vo.setOwnerId(record.getOwnerId());
        vo.setUrl(record.getScope() == DataScope.PUBLIC
                && record.getFileKey() != null
                && !record.getFileKey().startsWith(FileStorageConfig.PRIVATE_DOCUMENT_PREFIX)
                ? config.getUrlPrefix() + "/" + record.getFileKey() : null);
        vo.setCreatorId(record.getCreatorId());
        vo.setCreateTime(record.getCreateTime());
        return vo;
    }
}
