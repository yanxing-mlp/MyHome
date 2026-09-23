package com.familyhome.album.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.album.biz.dao.AlbumGroupMapper;
import com.familyhome.album.biz.dao.AlbumImageGroupRelMapper;
import com.familyhome.album.biz.dao.AlbumImageMapper;
import com.familyhome.album.biz.entity.AlbumGroupDO;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.album.biz.entity.AlbumImageGroupRelDO;
import com.familyhome.album.biz.model.vo.admin.AlbumImageVO;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.dto.FileDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 图片读写与关系覆盖均限定当前分区，creatorId 保留上传人，不参与个人图片属主判断。 */
@Service
@RequiredArgsConstructor
public class AlbumImageService {

    private final AlbumImageMapper imageMapper;
    private final AlbumGroupMapper groupMapper;
    private final AlbumImageGroupRelMapper relMapper;
    private final FileFacade fileFacade;
    private final AlbumCityService cityService;
    private final AlbumAccessService accessService;
    private final AlbumImageDeletionService deletionService;

    /** C 端须指定上架分组或家庭 ungrouped，且只能查询在架图片；B 端可以查本分区全部。 */
    public Page<AlbumImageVO> pageForMp(Long groupId, boolean ungrouped, String city, ContentStatus status,
                                      long pageNo, int pageSize, AlbumScope scope, boolean onShelfOnly) {
        if (groupId != null) {
            accessService.requireGroup(groupId, scope, onShelfOnly);
        }
        AlbumPartition partition = AlbumPartition.forRequest(scope);
        if (ungrouped && partition.scope() != AlbumScope.FAMILY) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "个人相册不支持其他分组");
        }
        if (onShelfOnly && groupId == null && !ungrouped) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择相册");
        }
        var wrapper = partition.images();
        if (groupId != null) {
            wrapper.exists("SELECT 1 FROM album_image_group_rel rel"
                    + " WHERE rel.image_id = album_image.id AND rel.group_id = {0}", groupId);
        } else if (ungrouped) {
            wrapper.notExists("SELECT 1 FROM album_image_group_rel rel WHERE rel.image_id = album_image.id");
        }
        if (city != null && !city.isBlank()) {
            wrapper.eq(AlbumImageDO::getCity, city);
        }
        if (onShelfOnly) {
            wrapper.eq(AlbumImageDO::getStatus, ContentStatus.ON_SHELF);
        } else if (status != null) {
            wrapper.eq(AlbumImageDO::getStatus, status);
        }
        wrapper.orderByDesc(AlbumImageDO::getPinned).orderByDesc(AlbumImageDO::getCreateTime)
                .orderByDesc(AlbumImageDO::getId);
        Page<AlbumImageDO> doPage = imageMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<Long> fileIds = doPage.getRecords().stream().map(AlbumImageDO::getFileId).distinct().toList();
        Map<Long, FileDTO> files = fileIds.isEmpty() ? Map.of() : fileFacade.mapByIds(fileIds);
        Map<Long, List<Long>> groupIdsByImage = mapGroupIdsByImageIds(
                doPage.getRecords().stream().map(AlbumImageDO::getId).toList(), partition);
        List<AlbumImageVO> vos = new ArrayList<>();
        for (AlbumImageDO image : doPage.getRecords()) {
            vos.add(toVO(image, files, groupIdsByImage.getOrDefault(image.getId(), List.of())));
        }
        Page<AlbumImageVO> result = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        result.setRecords(vos);
        return result;
    }

    /** 只改城市/上下架，不允许 DELETED 绕过删除链路，也不允许改 scope/owner。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void update(Long id, String city, ContentStatus status, AlbumScope scope) {
        AlbumImageDO image = accessService.requireImage(id, scope);
        AlbumAccessService.requireShelfStatus(status);
        if (city == null && status == null) {
            return;
        }
        int updated = imageMapper.update(null, new LambdaUpdateWrapper<AlbumImageDO>()
                .eq(AlbumImageDO::getId, id)
                .ne(AlbumImageDO::getStatus, ContentStatus.DELETED)
                .set(city != null, AlbumImageDO::getCity, city)
                .set(status != null, AlbumImageDO::getStatus, status));
        if (updated == 0) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
        cityService.recalculate(AlbumPartition.of(image));
    }

    /** 显式保留 update_time，置顶不改变图片元数据时间。 */
    @Transactional
    public void togglePin(Long id, boolean pinned, AlbumScope scope) {
        accessService.requireImage(id, scope);
        int updated = imageMapper.update(null, new LambdaUpdateWrapper<AlbumImageDO>()
                .eq(AlbumImageDO::getId, id)
                .ne(AlbumImageDO::getStatus, ContentStatus.DELETED)
                .set(AlbumImageDO::getPinned, pinned ? 1 : 0)
                .setSql("update_time = update_time"));
        if (updated == 0) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long id, AlbumScope scope) {
        AlbumImageDO image = accessService.requireImage(id, scope);
        deletionService.deleteImages(List.of(image), AlbumPartition.of(image));
    }

    /** 所有请求 ID 验证完成后才开始软删，非法或不可访问 ID 不得被静默忽略。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void batchDelete(List<Long> ids, AlbumScope scope) {
        if (ids == null || ids.isEmpty()) {
            AlbumPartition.forRequest(scope);
            return;
        }
        List<AlbumImageDO> images = ids.stream().distinct()
                .map(id -> accessService.requireImage(id, scope)).toList();
        deletionService.deleteImages(images, AlbumPartition.of(images.getFirst()));
    }

    /** 空列表也必须先验证图片存在性和所属分区。 */
    public List<Long> getImageGroupIds(Long imageId, AlbumScope scope) {
        AlbumImageDO image = accessService.requireImage(imageId, scope);
        return mapGroupIdsByImageIds(List.of(imageId), AlbumPartition.of(image)).getOrDefault(imageId, List.of());
    }

    /** 先验证图片及全部目标组，再覆盖关系；禁止跨 scope 或个人属主关联。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void setImageGroups(Long imageId, List<Long> groupIds, AlbumScope scope) {
        AlbumImageDO image = accessService.requireImage(imageId, scope);
        List<Long> targets = groupIds == null ? List.of() : groupIds.stream().distinct()
                .sorted(Comparator.nullsFirst(Long::compareTo)).toList();
        for (Long groupId : targets) {
            accessService.requireGroupForUpdate(groupId, scope, false);
        }
        // 与绑定/删除采用同一顺序；空目标列表也必须等文件锁后确认图片未删。
        fileFacade.lockByIds(List.of(image.getFileId()));
        int updated = imageMapper.update(null, new LambdaUpdateWrapper<AlbumImageDO>()
                .eq(AlbumImageDO::getId, imageId)
                .ne(AlbumImageDO::getStatus, ContentStatus.DELETED)
                .setSql("update_time = update_time"));
        if (updated == 0) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
        relMapper.delete(new LambdaQueryWrapper<AlbumImageGroupRelDO>()
                .eq(AlbumImageGroupRelDO::getImageId, imageId));
        for (Long groupId : targets) {
            AlbumImageGroupRelDO rel = new AlbumImageGroupRelDO();
            rel.setImageId(imageId);
            rel.setGroupId(groupId);
            relMapper.insert(rel);
        }
    }

    /** 关系只返回当前分区未删除分组，防止异常历史关系泄漏其他分区 ID。 */
    private Map<Long, List<Long>> mapGroupIdsByImageIds(List<Long> imageIds, AlbumPartition partition) {
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        List<Long> groupIds = groupMapper.selectList(partition.groups().select(AlbumGroupDO::getId))
                .stream().map(AlbumGroupDO::getId).toList();
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return relMapper.selectList(new LambdaQueryWrapper<AlbumImageGroupRelDO>()
                        .in(AlbumImageGroupRelDO::getImageId, imageIds)
                        .in(AlbumImageGroupRelDO::getGroupId, groupIds))
                .stream().collect(Collectors.groupingBy(AlbumImageGroupRelDO::getImageId,
                        Collectors.mapping(AlbumImageGroupRelDO::getGroupId, Collectors.toList())));
    }

    private AlbumImageVO toVO(AlbumImageDO image, Map<Long, FileDTO> files, List<Long> groupIds) {
        AlbumImageVO vo = new AlbumImageVO();
        vo.setId(image.getId());
        vo.setGroupIds(groupIds);
        vo.setFileId(image.getFileId());
        vo.setCity(image.getCity());
        vo.setLng(image.getLng());
        vo.setLat(image.getLat());
        vo.setShootTime(image.getShootTime());
        vo.setStatus(image.getStatus());
        vo.setPinned(image.getPinned());
        vo.setCreatorId(image.getCreatorId());
        vo.setCreateTime(image.getCreateTime());
        vo.setUpdateTime(image.getUpdateTime());
        FileDTO file = files.get(image.getFileId());
        if (file != null) {
            vo.setUrl(file.getUrl());
            vo.setThumbUrl(file.getThumbUrl());
            vo.setWidth(file.getWidth());
            vo.setHeight(file.getHeight());
        }
        return vo;
    }
}
