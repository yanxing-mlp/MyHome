package com.familyhome.album.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.album.biz.dao.AlbumImageGroupRelMapper;
import com.familyhome.album.biz.dao.AlbumImageMapper;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.album.biz.entity.AlbumImageGroupRelDO;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 相册域共用删除链路；历史拆分图片可能共享 fileId，只有全相册域零活引用才交文件域清理。 */
@Service
@RequiredArgsConstructor
public class AlbumImageDeletionService {

    private final AlbumImageMapper imageMapper;
    private final AlbumImageGroupRelMapper relMapper;
    private final FileFacade fileFacade;
    private final AlbumCityService cityService;

    /** 调用方须先验证所有请求 ID，且在调用前不能持有图片或关系写锁。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteImages(List<AlbumImageDO> images, AlbumPartition partition) {
        if (images.isEmpty()) {
            return;
        }
        for (AlbumImageDO image : images) {
            if (!partition.contains(image) || image.getStatus() == ContentStatus.DELETED) {
                throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
            }
        }
        List<Long> fileIds = images.stream().map(AlbumImageDO::getFileId).distinct().sorted().toList();
        List<Long> imageIds = images.stream().map(AlbumImageDO::getId).distinct().sorted().toList();
        // 与绑定共用文件锁；必须先于图片/关系写锁，覆盖软删到全域活引用检查的全过程。
        fileFacade.lockByIds(fileIds);
        // 不复用调用方等待前的 DO；RC 下重新确认每张图片仍未删除且属于同一分区、已锁文件。
        List<AlbumImageDO> currentImages = imageMapper.selectList(partition.images()
                .in(AlbumImageDO::getId, imageIds).in(AlbumImageDO::getFileId, fileIds));
        if (currentImages.size() != imageIds.size()) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
        int updated = imageMapper.update(null, new LambdaUpdateWrapper<AlbumImageDO>()
                .in(AlbumImageDO::getId, imageIds)
                .eq(AlbumImageDO::getScope, partition.scope())
                .eq(AlbumImageDO::getOwnerId, partition.ownerId())
                .ne(AlbumImageDO::getStatus, ContentStatus.DELETED)
                .set(AlbumImageDO::getStatus, ContentStatus.DELETED));
        if (updated != imageIds.size()) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
        relMapper.delete(new LambdaQueryWrapper<AlbumImageGroupRelDO>()
                .in(AlbumImageGroupRelDO::getImageId, imageIds));

        // 特意不加分区条件：OFF_SHELF 也是活引用，不可破坏其他分区迁移后仍共享的文件。
        Set<Long> referenced = imageMapper.selectList(new LambdaQueryWrapper<AlbumImageDO>()
                        .select(AlbumImageDO::getFileId)
                        .in(AlbumImageDO::getFileId, fileIds)
                        .ne(AlbumImageDO::getStatus, ContentStatus.DELETED))
                .stream().map(AlbumImageDO::getFileId).collect(Collectors.toSet());
        List<Long> unreferenced = fileIds.stream().filter(id -> !referenced.contains(id)).toList();
        if (!unreferenced.isEmpty()) {
            fileFacade.markDeletedAndPurge(unreferenced);
        }
        cityService.recalculate(partition);
    }
}
