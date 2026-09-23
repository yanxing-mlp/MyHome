package com.familyhome.album.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.album.api.dto.AlbumImageBindRequest;
import com.familyhome.album.biz.dao.AlbumGroupMapper;
import com.familyhome.album.biz.dao.AlbumImageGroupRelMapper;
import com.familyhome.album.biz.dao.AlbumImageMapper;
import com.familyhome.album.biz.entity.AlbumGroupDO;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.album.biz.entity.AlbumImageGroupRelDO;
import com.familyhome.album.biz.model.vo.admin.AlbumGroupVO;
import com.familyhome.album.biz.model.vo.client.AlbumGroupCoverVO;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.dto.FileDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分组 CRUD、分区内多分组关联与封面统计。所有读写限定 FAMILY 或当前账号的 PERSONAL。
 * 分组下架只影响 C 端，不改变图片状态；删除则级联本分区图片，文件须全域零活引用才清理。
 * 城市统计按图片状态计数，仅重算受影响分区。
 */
@Service
@RequiredArgsConstructor
public class AlbumGroupService {

    private static final int COVER_LIMIT = 3;
    private static final String UNGROUPED_GROUP_NAME = "其他";

    private final AlbumGroupMapper groupMapper;
    private final AlbumImageMapper imageMapper;
    private final AlbumImageGroupRelMapper relMapper;
    private final FileFacade fileFacade;
    private final AlbumCityService cityService;
    private final AlbumAccessService accessService;
    private final AlbumImageDeletionService deletionService;

    /** B 端列表含下架，默认只列家庭档；私人档缺身份返回 401，绝不混合两档。 */
    public List<AlbumGroupVO> list(String keyword, AlbumScope scope) {
        AlbumPartition partition = AlbumPartition.forRequest(scope);
        var wrapper = partition.groups();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(AlbumGroupDO::getName, keyword);
        }
        List<AlbumGroupDO> groups = groupMapper.selectList(wrapper
                .orderByDesc(AlbumGroupDO::getSort).orderByDesc(AlbumGroupDO::getCreateTime));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AlbumImageDO>> imagesByGroup = bucketImagesByGroup(partition, false).byGroup();
        List<AlbumGroupVO> result = new ArrayList<>(groups.size());
        for (AlbumGroupDO group : groups) {
            AlbumGroupVO vo = new AlbumGroupVO();
            vo.setId(group.getId());
            vo.setName(group.getName());
            vo.setSort(group.getSort());
            vo.setStatus(group.getStatus());
            vo.setScope(group.getScope());
            vo.setImageCount(imagesByGroup.getOrDefault(group.getId(), List.of()).size());
            vo.setCreatorId(group.getCreatorId());
            vo.setCreateTime(group.getCreateTime());
            vo.setUpdateTime(group.getUpdateTime());
            result.add(vo);
        }
        return result;
    }

    /** C 端只出上架分组和图片；家庭即使没有分组，也可以有「其他」卡，私人不出此卡。 */
    public List<AlbumGroupCoverVO> listCovers(AlbumScope scope) {
        AlbumPartition partition = AlbumPartition.forRequest(scope);
        List<AlbumGroupDO> groups = groupMapper.selectList(partition.groups()
                .eq(AlbumGroupDO::getStatus, ContentStatus.ON_SHELF)
                .orderByDesc(AlbumGroupDO::getSort).orderByDesc(AlbumGroupDO::getCreateTime));
        GroupedImages grouped = bucketImagesByGroup(partition, true);
        List<Long> coverFileIds = new ArrayList<>();
        for (AlbumGroupDO group : groups) {
            coverFileIds.addAll(imageFileIds(grouped.byGroup().getOrDefault(group.getId(), List.of())));
        }
        if (partition.scope() == AlbumScope.FAMILY) {
            coverFileIds.addAll(imageFileIds(grouped.ungrouped()));
        }
        Map<Long, FileDTO> files = coverFileIds.isEmpty() ? Map.of()
                : fileFacade.mapByIds(coverFileIds.stream().distinct().toList());
        List<AlbumGroupCoverVO> result = new ArrayList<>();
        for (AlbumGroupDO group : groups) {
            List<AlbumImageDO> images = grouped.byGroup().getOrDefault(group.getId(), List.of());
            if (!images.isEmpty()) {
                result.add(toCoverVO(group.getId(), group.getName(), images, files));
            }
        }
        if (partition.scope() == AlbumScope.FAMILY && !grouped.ungrouped().isEmpty()) {
            result.add(toCoverVO(null, UNGROUPED_GROUP_NAME, grouped.ungrouped(), files));
        }
        return result;
    }

    /** 默认上架，max(sort) 仅取当前分区未删除分组；scope 创建后不可改。 */
    @Transactional
    public Long create(String name, AlbumScope scope) {
        Long creatorId = CurrentUserHolder.requireUserId();
        AlbumPartition partition = AlbumPartition.forRequest(scope);
        String trimmed = name == null ? "" : name.trim();
        requireNameUnused(trimmed, null, partition);
        int maxSort = groupMapper.selectList(partition.groups()).stream()
                .map(AlbumGroupDO::getSort).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
        AlbumGroupDO group = new AlbumGroupDO();
        group.setName(trimmed);
        group.setSort(maxSort + 1);
        group.setStatus(ContentStatus.ON_SHELF);
        group.setScope(partition.scope());
        group.setCreatorId(creatorId);
        groupMapper.insert(group);
        return group.getId();
    }

    @Transactional
    public void update(Long id, String name, ContentStatus status, AlbumScope scope) {
        accessService.requireGroup(id, scope, false);
        AlbumAccessService.requireShelfStatus(status);
        if (name == null && status == null) {
            return;
        }
        String trimmed = name == null ? null : name.trim();
        if (trimmed != null) {
            requireNameUnused(trimmed, id, AlbumPartition.forRequest(scope));
        }
        int updated = groupMapper.update(null, new LambdaUpdateWrapper<AlbumGroupDO>()
                .eq(AlbumGroupDO::getId, id)
                .ne(AlbumGroupDO::getStatus, ContentStatus.DELETED)
                .set(trimmed != null, AlbumGroupDO::getName, trimmed)
                .set(status != null, AlbumGroupDO::getStatus, status));
        if (updated == 0) {
            throw BizException.notFound(ErrorCode.ALBUM_GROUP_NOT_FOUND, "分组不存在");
        }
    }

    /** 整批先验证存在性、分区和重复 ID，任何失败均不得先写入前面的项。 */
    @Transactional
    public void batchUpdateSort(List<SortItem> items, AlbumScope scope) {
        if (items == null || items.isEmpty()) {
            AlbumPartition.forRequest(scope);
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (SortItem item : items) {
            if (item == null || item.getId() == null || item.getSort() == null || !ids.add(item.getId())) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "排序项不能为空或包含重复分组");
            }
            accessService.requireGroup(item.getId(), scope, false);
        }
        for (SortItem item : items.stream().sorted(Comparator.comparing(SortItem::getId)).toList()) {
            int updated = groupMapper.update(null, new LambdaUpdateWrapper<AlbumGroupDO>()
                    .eq(AlbumGroupDO::getId, item.getId())
                    .ne(AlbumGroupDO::getStatus, ContentStatus.DELETED)
                    .set(AlbumGroupDO::getSort, item.getSort()));
            if (updated == 0) {
                throw BizException.notFound(ErrorCode.ALBUM_GROUP_NOT_FOUND, "分组不存在");
            }
        }
    }

    /** 删除分组会连带删除本分区成员图片及其所有组关系，而不删除其他分区的图片行。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long id, AlbumScope scope) {
        AlbumGroupDO group = accessService.requireGroupForUpdate(id, scope, false);
        AlbumPartition partition = AlbumPartition.of(group);
        List<AlbumImageDO> images = listGroupImageRows(id, partition);
        // 组锁 -> 全部文件锁 -> 图片/关系写锁，不能提前删除关系再等文件锁。
        deletionService.deleteImages(images, partition);
        int updated = groupMapper.update(null, new LambdaUpdateWrapper<AlbumGroupDO>()
                .eq(AlbumGroupDO::getId, id)
                .ne(AlbumGroupDO::getStatus, ContentStatus.DELETED)
                .set(AlbumGroupDO::getStatus, ContentStatus.DELETED));
        if (updated == 0) {
            throw BizException.notFound(ErrorCode.ALBUM_GROUP_NOT_FOUND, "分组不存在");
        }
        relMapper.delete(new LambdaQueryWrapper<AlbumImageGroupRelDO>()
                .eq(AlbumImageGroupRelDO::getGroupId, id));
    }

    /**
     * 先验证整批文件，再按当前分区 fileId 复用图片；md5 只用于目标组去重。
     * 仅其他分区拥有的 fileId 不可复制绑定，必须重新上传取得独立 fileId。
     * 迁移后当前分区已有共享 fileId 的行仍可复用；新文件必须是当前账号上传的相册图片。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchBindResult bindImages(Long groupId, List<AlbumImageBindRequest> items,
                                      AlbumScope scope, boolean onShelfOnly) {
        AlbumGroupDO group = accessService.requireGroupForUpdate(groupId, scope, onShelfOnly);
        AlbumPartition partition = AlbumPartition.of(group);
        if (items == null || items.isEmpty()) {
            return new BatchBindResult(0, List.of());
        }
        for (AlbumImageBindRequest item : items) {
            if (item == null || item.getFileId() == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "文件 ID 不能为空");
            }
        }
        List<Long> pendingFileIds = items.stream().map(AlbumImageBindRequest::getFileId)
                .distinct().sorted().toList();
        // 先锁组再按 ID 升序批量锁文件；RC 保证等待后看见其他绑定/删除事务提交的归属。
        fileFacade.lockByIds(pendingFileIds);
        Map<Long, FileDTO> pendingFiles = fileFacade.mapByIds(pendingFileIds);
        Map<Long, AlbumImageDO> rowByFileId = imageMapper.selectList(partition.images()
                        .in(AlbumImageDO::getFileId, pendingFileIds).orderByAsc(AlbumImageDO::getId))
                .stream().collect(Collectors.toMap(AlbumImageDO::getFileId, row -> row, (first, duplicate) -> first));
        // 不加状态条件：即使别分区的旧行已删，只要文件仍存在，也不能把旧 fileId 跨区搬运。
        Set<Long> foreignFileIds = imageMapper.selectList(new LambdaQueryWrapper<AlbumImageDO>()
                        .select(AlbumImageDO::getFileId, AlbumImageDO::getScope, AlbumImageDO::getOwnerId)
                        .in(AlbumImageDO::getFileId, pendingFileIds))
                .stream().filter(image -> !partition.contains(image))
                .map(AlbumImageDO::getFileId).collect(Collectors.toSet());
        for (Long fileId : pendingFileIds) {
            AlbumImageDO existing = rowByFileId.get(fileId);
            FileDTO file = pendingFiles.get(fileId);
            // 只兼容当前分区已存在的历史图片；旧 album 类型不能作为新文件领取。
            if (file == null || !("ALBUM_IMAGE".equals(file.getBizType())
                    || existing != null && "album".equals(file.getBizType()))) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "文件不存在或不是相册图片");
            }
            if (existing == null) {
                if (foreignFileIds.contains(fileId)) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "文件不属于当前相册分区，请重新上传");
                }
                if (!CurrentUserHolder.requireUserId().equals(file.getCreatorId())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "只能绑定当前账号上传的文件");
                }
            } else if (onShelfOnly && existing.getStatus() != ContentStatus.ON_SHELF) {
                throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
            }
        }

        List<AlbumImageDO> groupImages = listGroupImageRows(groupId, partition);
        Set<Long> existingFileIds = groupImages.stream().map(AlbumImageDO::getFileId).collect(Collectors.toSet());
        Set<Long> linkedImageIds = groupImages.stream().map(AlbumImageDO::getId).collect(Collectors.toSet());
        Set<String> md5s = existingFileIds.isEmpty() ? new HashSet<>()
                : fileFacade.mapByIds(new ArrayList<>(existingFileIds)).values().stream()
                        .map(FileDTO::getMd5).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Long> skipped = new ArrayList<>();
        int added = 0;
        boolean created = false;
        for (AlbumImageBindRequest item : items) {
            Long fileId = item.getFileId();
            FileDTO file = pendingFiles.get(fileId);
            if (existingFileIds.contains(fileId) || file.getMd5() != null && md5s.contains(file.getMd5())) {
                skipped.add(fileId);
                continue;
            }
            existingFileIds.add(fileId);
            if (file.getMd5() != null) {
                md5s.add(file.getMd5());
            }
            AlbumImageDO image = rowByFileId.get(fileId);
            if (image == null) {
                image = new AlbumImageDO();
                image.setFileId(fileId);
                image.setScope(partition.scope());
                image.setOwnerId(partition.ownerId());
                image.setCreatorId(file.getCreatorId());
                image.setCity(item.getCity());
                image.setLng(item.getLng());
                image.setLat(item.getLat());
                image.setShootTime(item.getShootTime());
                image.setStatus(ContentStatus.ON_SHELF);
                image.setPinned(0);
                imageMapper.insert(image);
                rowByFileId.put(fileId, image);
                created = true;
            }
            if (linkedImageIds.add(image.getId())) {
                AlbumImageGroupRelDO rel = new AlbumImageGroupRelDO();
                rel.setImageId(image.getId());
                rel.setGroupId(groupId);
                relMapper.insert(rel);
                added++;
            }
        }
        if (created) {
            cityService.recalculate(partition);
        }
        return new BatchBindResult(added, skipped);
    }

    /** 同分区未删除分组占名（含下架）；家庭共享名称，个人只检查当前账号。 */
    private void requireNameUnused(String name, Long excludeId, AlbumPartition partition) {
        if (name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "分组名不能为空");
        }
        var wrapper = partition.groups()
                .apply("TRIM(name) = {0}", name)
                .ne(excludeId != null, AlbumGroupDO::getId, excludeId);
        if (groupMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.ALBUM_GROUP_NAME_DUPLICATED, "当前相册已有同名分组");
        }
    }

    private List<AlbumImageDO> listGroupImageRows(Long groupId, AlbumPartition partition) {
        return imageMapper.selectList(partition.images()
                .exists("SELECT 1 FROM album_image_group_rel rel"
                        + " WHERE rel.image_id = album_image.id AND rel.group_id = {0}", groupId)
                .orderByDesc(AlbumImageDO::getPinned).orderByDesc(AlbumImageDO::getCreateTime)
                .orderByDesc(AlbumImageDO::getId));
    }

    private record GroupedImages(Map<Long, List<AlbumImageDO>> byGroup, List<AlbumImageDO> ungrouped) {
    }

    /** 只扫当前分区；下架组仍有关系，因此它的图片不会掉入家庭「其他」。 */
    private GroupedImages bucketImagesByGroup(AlbumPartition partition, boolean onShelfOnly) {
        var wrapper = partition.images().select(AlbumImageDO::getId, AlbumImageDO::getFileId)
                .eq(onShelfOnly, AlbumImageDO::getStatus, ContentStatus.ON_SHELF)
                .orderByDesc(AlbumImageDO::getPinned).orderByDesc(AlbumImageDO::getCreateTime)
                .orderByDesc(AlbumImageDO::getId);
        List<AlbumImageDO> images = imageMapper.selectList(wrapper);
        if (images.isEmpty()) {
            return new GroupedImages(Map.of(), List.of());
        }
        Map<Long, List<Long>> groupIdsByImage = relMapper.selectList(new LambdaQueryWrapper<AlbumImageGroupRelDO>()
                        .in(AlbumImageGroupRelDO::getImageId, images.stream().map(AlbumImageDO::getId).toList()))
                .stream().collect(Collectors.groupingBy(AlbumImageGroupRelDO::getImageId,
                        Collectors.mapping(AlbumImageGroupRelDO::getGroupId, Collectors.toList())));
        Map<Long, List<AlbumImageDO>> byGroup = new HashMap<>();
        List<AlbumImageDO> ungrouped = new ArrayList<>();
        for (AlbumImageDO image : images) {
            List<Long> groupIds = groupIdsByImage.getOrDefault(image.getId(), List.of());
            if (groupIds.isEmpty()) {
                ungrouped.add(image);
            } else {
                groupIds.forEach(id -> byGroup.computeIfAbsent(id, key -> new ArrayList<>()).add(image));
            }
        }
        return new GroupedImages(byGroup, ungrouped);
    }

    private static List<Long> imageFileIds(List<AlbumImageDO> images) {
        return images.stream().limit(COVER_LIMIT).map(AlbumImageDO::getFileId).toList();
    }

    private static AlbumGroupCoverVO toCoverVO(Long groupId, String name, List<AlbumImageDO> images,
                                               Map<Long, FileDTO> files) {
        List<String> urls = new ArrayList<>();
        for (Long fileId : imageFileIds(images)) {
            FileDTO file = files.get(fileId);
            if (file != null && file.getThumbUrl() != null && !file.getThumbUrl().isBlank()) {
                urls.add(file.getThumbUrl());
            }
        }
        AlbumGroupCoverVO vo = new AlbumGroupCoverVO();
        vo.setGroupId(groupId);
        vo.setName(name);
        vo.setImageCount(images.size());
        vo.setCoverThumbUrls(urls);
        return vo;
    }

    @Data
    public static class SortItem {
        private Long id;
        private Integer sort;
    }

    @Data
    public static class BatchBindResult {
        private final int added;
        private final List<Long> skippedDuplicates;
    }
}
