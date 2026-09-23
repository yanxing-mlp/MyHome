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
import com.familyhome.file.biz.model.bo.VideoType;
import com.familyhome.file.biz.model.vo.admin.VideoVO;
import com.familyhome.file.biz.storage.FileStorageWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频管理服务（B 端"视频管理"页：公共视频 / 个人视频）。
 *
 * <p><b>不另建业务表</b>：视频与文档一样，元数据（名字、大小、md5、mime、物理路径、分区、添加人）
 * {@code file_object} 全都有，用 {@code biz_type = 'video'} 与文档（{@code 'document'}）、图片区分即可
 * （V107 放开了"私人分区只能是 document"那条约束，让私人视频也进得来）。与 {@link DocumentFileService}
 * 当初"文档不建表"是同一个取舍。
 *
 * <p><b>与文档的两处关键差异</b>：
 * <ol>
 *   <li><b>流式落盘</b>：视频几百 MB，不能像文档那样 {@code file.getBytes()} 整块读进内存，
 *       走 {@link FileStorageWriter#writeVideoStream}（边写边算 md5，不秒传）。</li>
 *   <li><b>播放而非下载</b>：文档是 {@code Content-Disposition: attachment} 让人下载；视频要能<b>在线播放并 seek</b>，
 *       所以走带 HTTP Range 的流式接口 + 短时签名票据（{@link VideoPlayTicket}），原因见该类的类注释。</li>
 * </ol>
 *
 * <p><b>分区口径与文档/密码本完全一致</b>：PUBLIC/0 全家共享、PRIVATE/当前账号隔离，ADMIN 无私人读取特权，
 * 分区与属主由服务端按登录态设定、不由请求指定，跨分区/跨属主的裸 id 一律 404。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    /** file_object.biz_type 的视频取值；本常量是唯一的等值判断口径。 */
    public static final String BIZ_TYPE_VIDEO = "video";

    /**
     * 播放流的根相对基址。B 端与 C 端各一条自有路径（{@code /api/b/video} vs {@code /api/c/video}），
     * 票据签发与校验逻辑完全一致，只是前缀不同——C 端不复用 B 端路径（方案"C 端自有接口层"）。
     */
    private static final String STREAM_BASE_B = "/api/b/video";
    private static final String STREAM_BASE_C = "/api/c/video";

    private final FileObjectMapper fileObjectMapper;
    private final FileStorageWriter storageWriter;
    private final FileFacade fileFacade;
    private final FileStorageConfig config;
    private final VideoPlayTicket playTicket;

    /** 列表：最近上传在前，不分页（家庭量级）。每行带一枚现签的短时播放票据（B 端播放路径）。 */
    public List<VideoVO> list(DataScope scope) {
        return list(scope, STREAM_BASE_B);
    }

    /**
     * C 端列表：查询、分区、票据与 B 端 {@link #list(DataScope)} 是同一套，只把每行 {@code playUrl}
     * 指向 C 端自有的 {@code /api/c/video/...} 播放流。返回的仍是 {@link VideoVO}，字段裁剪交给
     * {@code VideoCController}（与相册 C 端在 controller 里把 {@code AlbumImageVO} 裁成 BriefVO 同一口径）。
     */
    public List<VideoVO> listForClient(DataScope scope) {
        return list(scope, STREAM_BASE_C);
    }

    private List<VideoVO> list(DataScope scope, String streamBase) {
        DataPartition partition = DataPartition.forRequest(scope);
        List<FileObjectDO> rows = fileObjectMapper.selectList(videoQuery(partition)
                .orderByDesc(FileObjectDO::getId));
        return rows.stream().map(record -> toVO(record, streamBase)).toList();
    }

    /**
     * 上传一支视频。
     *
     * <p>顺序：先解析/校验类型（非视频当场 415，一分磁盘都不碰）→ 流式写盘 → 落库；
     * 落库失败由 {@link FileStorageWriter#writeVideoStream} 注册的回滚回调清掉刚写的文件。
     */
    @Transactional
    public VideoVO upload(MultipartFile file, DataScope scope) {
        DataPartition partition = DataPartition.forRequest(scope);
        String originName = file.getOriginalFilename();
        VideoType type = VideoType.resolve(originName);

        FileStorageWriter.Written written;
        try (InputStream in = file.getInputStream()) {
            written = storageWriter.writeVideoStream(in, type.ext(), partition);
        } catch (IOException e) {
            log.error("读取上传视频失败: name={}", originName, e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "读取视频失败");
        }

        FileObjectDO record = new FileObjectDO();
        record.setFileKey(written.getFileKey());
        record.setThumbKey(null);
        record.setOriginName(originName);
        record.setMd5(written.getMd5());
        record.setMimeType(type.mimeType());
        record.setExt(type.ext());
        record.setFileSize(file.getSize());
        record.setWidth(null);
        record.setHeight(null);
        record.setHardLink(0);
        record.setBizType(BIZ_TYPE_VIDEO);
        record.setCategoryId(null);
        record.setScope(partition.scope());
        record.setOwnerId(partition.ownerId());
        record.setCreatorId(CurrentUserHolder.requireUserId());
        try {
            fileObjectMapper.insert(record);
            log.info("视频上传完成: id={}, name={}, ext={}, size={}, scope={}, ownerId={}",
                    record.getId(), originName, type.ext(), file.getSize(),
                    partition.scope(), partition.ownerId());
            return toVO(requireVideo(record.getId(), partition), STREAM_BASE_B);
        } catch (RuntimeException e) {
            storageWriter.discard(written.getFileKey(), null);
            throw e;
        }
    }

    /** 删除：软删记录 + 提交后物理删文件（口径与文档/图片一致，复用 {@link FileFacade#markDeletedAndPurge}）。 */
    @Transactional
    public void delete(Long id, DataScope scope) {
        FileObjectDO record = requireVideo(id, DataPartition.forRequest(scope));
        fileFacade.markDeletedAndPurge(List.of(id));
        log.info("删除视频: id={}, name={}", id, record.getOriginName());
    }

    /**
     * 播放流：验票据（不验登录令牌——{@code <video>} 发不出 Authorization 头）→ 取行 → 吐字节流。
     *
     * <p><b>Range 支持</b>：返回 {@link ResponseEntity} 包 {@link FileSystemResource}，Spring MVC 见到请求里的
     * {@code Range} 头会自动切片并回 206 Partial Content（{@code AbstractMessageConverterMethodProcessor} 内建），
     * 所以进度条拖动 / 快进能直接用，不需要手写 Range 解析。必须用 {@link FileSystemResource}（能报 contentLength、
     * 能重复打开流），不能用 {@code InputStreamResource}（一次性流，切片会失败）。
     *
     * <p>票据里绑定了 id + scope + ownerId，这里再用它去查行（而不是相信票据里的分区就直接读盘），
     * 保证"票据对得上、行也还在、且确实属于那个分区"三者一致，任一不满足都 404。
     */
    public ResponseEntity<Resource> stream(Long id, String ticket) {
        VideoPlayTicket.Claim claim = playTicket.verify(ticket, id);
        FileObjectDO record = fileObjectMapper.selectOne(new LambdaQueryWrapper<FileObjectDO>()
                .eq(FileObjectDO::getBizType, BIZ_TYPE_VIDEO)
                .eq(FileObjectDO::getId, claim.videoId())
                .eq(FileObjectDO::getScope, claim.scope())
                .eq(FileObjectDO::getOwnerId, claim.ownerId())
                .eq(FileObjectDO::getDeleted, 0));
        if (record == null) {
            throw videoNotFound();
        }
        String key = record.getFileKey();
        // 防止错误元数据把 PUBLIC 行指向私人路径，或把私人行指向另一账号目录（与文档下载同一道校验）。
        String expectedPrivatePrefix = claim.scope() == DataScope.PRIVATE
                ? FileStorageConfig.PRIVATE_VIDEO_PREFIX + claim.ownerId() + "/" : null;
        if (key == null || (expectedPrivatePrefix != null
                ? !key.startsWith(expectedPrivatePrefix)
                : FileStorageConfig.privatePrefixOf(key) != null)) {
            throw videoNotFound();
        }
        try {
            Path path = config.resolvePath(key);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw videoNotFound();
            }
            MediaType mediaType;
            try {
                mediaType = record.getMimeType() != null
                        ? MediaType.parseMediaType(record.getMimeType())
                        : MediaType.APPLICATION_OCTET_STREAM;
            } catch (RuntimeException e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            // inline 而非 attachment：让浏览器/播放器就地解码播放，不触发下载。
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new FileSystemResource(path));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            throw videoNotFound();
        }
    }

    private LambdaQueryWrapper<FileObjectDO> videoQuery(DataPartition partition) {
        return new LambdaQueryWrapper<FileObjectDO>()
                .eq(FileObjectDO::getBizType, BIZ_TYPE_VIDEO)
                .eq(FileObjectDO::getScope, partition.scope())
                .eq(FileObjectDO::getOwnerId, partition.ownerId())
                .eq(FileObjectDO::getDeleted, 0);
    }

    private FileObjectDO requireVideo(Long id, DataPartition partition) {
        FileObjectDO record = id == null ? null : fileObjectMapper.selectOne(
                videoQuery(partition).eq(FileObjectDO::getId, id));
        if (record == null || !BIZ_TYPE_VIDEO.equals(record.getBizType())
                || !partition.contains(record.getScope(), record.getOwnerId())
                || Integer.valueOf(1).equals(record.getDeleted())) {
            throw videoNotFound();
        }
        return record;
    }

    private BizException videoNotFound() {
        return BizException.of(ErrorCode.FILE_NOT_FOUND, "视频不存在或已被删除");
    }

    private VideoVO toVO(FileObjectDO record, String streamBase) {
        VideoVO vo = new VideoVO();
        vo.setId(record.getId());
        vo.setName(record.getOriginName());
        vo.setFileType(record.getExt() == null ? null : record.getExt().toUpperCase(Locale.ROOT));
        vo.setMimeType(record.getMimeType());
        vo.setFileSize(record.getFileSize());
        vo.setCreatorId(record.getCreatorId());
        vo.setScope(record.getScope());
        vo.setOwnerId(record.getOwnerId());
        vo.setCreateTime(record.getCreateTime());
        String ticket = playTicket.issue(record.getId(), record.getScope(), record.getOwnerId());
        vo.setPlayUrl(streamBase + "/" + record.getId() + "/stream?ticket=" + ticket);
        return vo;
    }
}
