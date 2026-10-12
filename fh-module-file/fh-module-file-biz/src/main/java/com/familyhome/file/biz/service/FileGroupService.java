package com.familyhome.file.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.biz.dao.FileGroupMapper;
import com.familyhome.file.biz.dao.FileGroupRelMapper;
import com.familyhome.file.biz.dao.FileObjectMapper;
import com.familyhome.file.biz.entity.FileGroupDO;
import com.familyhome.file.biz.entity.FileGroupRelDO;
import com.familyhome.file.biz.entity.FileObjectDO;
import com.familyhome.file.biz.model.vo.admin.FileGroupVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件/视频分组服务：一张 {@code file_group} 表用 {@code bizType}（{@code document}/{@code video}）
 * 区分两类分组，与 {@code file_object} 同域、同分区口径（{@link DataScope} PUBLIC/0 全家共享、
 * PRIVATE/当前账号隔离）。分组是<b>硬删除</b>（同 {@code file_category} 字典），删组时靠
 * {@code file_group_rel.group_id} 外键级联清关联行，不留相册 V205 那种孤儿关联。
 *
 * <p>与相册分组（{@code AlbumGroupService}）刻意保持同一套交互口径：列表带组内数量、拖拽整批重排、
 * 改名判重、文件多对多归属整组覆盖。差异只有两处——没有上下架三态（文件域无 C 端"下架"语义），
 * 关联表 {@code file_id} 指向 {@code file_object.id} 而非独立业务表。
 *
 * <p>关联行的清理有两条路：删<b>分组</b>由外键级联自动完成；删<b>文档/视频</b>是软删（{@code file_object}
 * 行仍在），级联不触发，所以 {@code DocumentFileService.delete}/{@code VideoService.delete} 必须显式调
 * {@link #deleteFileRelations(Long)}，否则关联行会残留（见 {@link FileGroupRelDO} 类注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileGroupService {

    /** 分组所属业务取值，与 {@code file_object.biz_type} 的文档/视频取值一致。 */
    public static final String BIZ_DOCUMENT = "document";
    public static final String BIZ_VIDEO = "video";

    private final FileGroupMapper groupMapper;
    private final FileGroupRelMapper relMapper;
    private final FileObjectMapper fileObjectMapper;

    /** B 端列表：某类分组在当前分区的全部行（不分页），按 sort 降序，带组内文件数。 */
    public List<FileGroupVO> list(String bizType, String keyword, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        LambdaQueryWrapper<FileGroupDO> wrapper = partitionQuery(bizType, partition);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(FileGroupDO::getName, keyword.trim());
        }
        List<FileGroupDO> groups = groupMapper.selectList(wrapper
                .orderByDesc(FileGroupDO::getSort).orderByDesc(FileGroupDO::getId));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> counts = countByGroup(bizType, partition,
                groups.stream().map(FileGroupDO::getId).toList());
        List<FileGroupVO> result = new ArrayList<>(groups.size());
        for (FileGroupDO group : groups) {
            FileGroupVO vo = new FileGroupVO();
            vo.setId(group.getId());
            vo.setName(group.getName());
            vo.setSort(group.getSort());
            vo.setBizType(group.getBizType());
            vo.setScope(group.getScope());
            vo.setFileCount(counts.getOrDefault(group.getId(), 0));
            vo.setCreatorId(group.getCreatorId());
            vo.setCreateTime(group.getCreateTime());
            vo.setUpdateTime(group.getUpdateTime());
            result.add(vo);
        }
        return result;
    }

    /** 新建分组：sort 取当前分区该类分组的 max+1；同分区同类判重。scope/bizType 创建后不可改。 */
    @Transactional
    public Long create(String bizType, String name, DataScope scope) {
        requireBizType(bizType);
        Long creatorId = CurrentUserHolder.requireUserId();
        DataPartition partition = DataPartition.forRequest(scope);
        String trimmed = name == null ? "" : name.trim();
        requireNameUnused(bizType, trimmed, null, partition);
        int maxSort = groupMapper.selectList(partitionQuery(bizType, partition)).stream()
                .map(FileGroupDO::getSort).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
        FileGroupDO group = new FileGroupDO();
        group.setBizType(bizType);
        group.setName(trimmed);
        group.setSort(maxSort + 1);
        group.setScope(partition.scope());
        group.setOwnerId(partition.ownerId());
        group.setCreatorId(creatorId);
        groupMapper.insert(group);
        log.info("新建文件分组: id={}, name={}, bizType={}, scope={}, ownerId={}",
                group.getId(), trimmed, bizType, partition.scope(), partition.ownerId());
        return group.getId();
    }

    /** 改名（分组没有上下架，只有名字可改）；null 表示不改。 */
    @Transactional
    public void update(Long id, String name, String bizType, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        requireGroup(id, bizType, partition);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        requireNameUnused(bizType, trimmed, id, partition);
        groupMapper.update(null, new LambdaUpdateWrapper<FileGroupDO>()
                .eq(FileGroupDO::getId, id)
                .set(FileGroupDO::getName, trimmed));
        log.info("重命名文件分组: id={}, name={}", id, trimmed);
    }

    /** 拖拽后整批重排：先验证每一项存在且属于当前分区+类型，任何一项失败都不得先写入前面的项。 */
    @Transactional
    public void batchUpdateSort(List<SortItem> items, String bizType, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (SortItem item : items) {
            if (item == null || item.getId() == null || item.getSort() == null || !ids.add(item.getId())) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "排序项不能为空或包含重复分组");
            }
            requireGroup(item.getId(), bizType, partition);
        }
        for (SortItem item : items) {
            groupMapper.update(null, new LambdaUpdateWrapper<FileGroupDO>()
                    .eq(FileGroupDO::getId, item.getId())
                    .set(FileGroupDO::getSort, item.getSort()));
        }
        log.info("文件分组重排: bizType={}, count={}", bizType, items.size());
    }

    /** 删除分组：硬删行，关联行由 {@code file_group_rel.group_id} 外键级联清掉。 */
    @Transactional
    public void delete(Long id, String bizType, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        requireGroup(id, bizType, partition);
        groupMapper.deleteById(id);
        log.info("删除文件分组: id={}, bizType={}", id, bizType);
    }

    /** 一个文件当前所属的分组 id（整组覆盖前给编辑弹窗回填用）。 */
    public List<Long> getFileGroups(Long fileId, String bizType, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        requireFile(fileId, bizType, partition);
        Set<Long> liveGroupIds = liveGroupIds(bizType, partition);
        return relMapper.selectList(new LambdaQueryWrapper<FileGroupRelDO>()
                        .eq(FileGroupRelDO::getFileId, fileId)).stream()
                .map(FileGroupRelDO::getGroupId)
                .filter(liveGroupIds::contains)
                .toList();
    }

    /** 整组覆盖一个文件的分组归属；目标分组必须都在同分区+同类型内，否则整批拒绝。 */
    @Transactional
    public void setFileGroups(Long fileId, List<Long> groupIds, String bizType, DataScope scope) {
        requireBizType(bizType);
        DataPartition partition = DataPartition.forRequest(scope);
        requireFile(fileId, bizType, partition);
        List<Long> target = normalize(groupIds);
        requireGroupsInPartition(target, bizType, partition);
        relMapper.delete(new LambdaQueryWrapper<FileGroupRelDO>().eq(FileGroupRelDO::getFileId, fileId));
        for (Long groupId : target) {
            FileGroupRelDO rel = new FileGroupRelDO();
            rel.setFileId(fileId);
            rel.setGroupId(groupId);
            relMapper.insert(rel);
        }
        log.info("设置文件所属分组: fileId={}, bizType={}, groupIds={}", fileId, bizType, target);
    }

    /**
     * 上传落库后绑定初始分组（此时文件还没有任何关联行）。返回规范化后真正绑定的分组 id，
     * 供上传接口直接回填 VO 的 groupIds/groupNames，省一次查询。
     */
    @Transactional
    public List<Long> bindNewFile(Long fileId, List<Long> groupIds, String bizType, DataPartition partition) {
        List<Long> target = normalize(groupIds);
        if (target.isEmpty()) {
            return List.of();
        }
        requireGroupsInPartition(target, bizType, partition);
        for (Long groupId : target) {
            FileGroupRelDO rel = new FileGroupRelDO();
            rel.setFileId(fileId);
            rel.setGroupId(groupId);
            relMapper.insert(rel);
        }
        return target;
    }

    /** 删文档/视频时清掉它的关联行（{@code file_object} 是软删，外键级联不触发）。 */
    @Transactional
    public void deleteFileRelations(Long fileId) {
        if (fileId == null) {
            return;
        }
        relMapper.delete(new LambdaQueryWrapper<FileGroupRelDO>().eq(FileGroupRelDO::getFileId, fileId));
    }

    // ========== 供文档/视频列表富化 VO 的批量读 ==========

    /** file_id → 该文件所属分组 id 列表（仅限当前分区+类型的存活分组）。 */
    public Map<Long, List<Long>> groupIdsByFile(String bizType, DataPartition partition, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> liveGroupIds = liveGroupIds(bizType, partition);
        if (liveGroupIds.isEmpty()) {
            return Map.of();
        }
        return relMapper.selectList(new LambdaQueryWrapper<FileGroupRelDO>()
                        .in(FileGroupRelDO::getFileId, fileIds)).stream()
                .filter(rel -> liveGroupIds.contains(rel.getGroupId()))
                .collect(Collectors.groupingBy(FileGroupRelDO::getFileId,
                        Collectors.mapping(FileGroupRelDO::getGroupId, Collectors.toList())));
    }

    /** 当前分区+类型下 group_id → 名称。 */
    public Map<Long, String> groupNameMap(String bizType, DataPartition partition) {
        return groupMapper.selectList(partitionQuery(bizType, partition)).stream()
                .collect(Collectors.toMap(FileGroupDO::getId, FileGroupDO::getName));
    }

    /** 供文档/视频列表按 groupId 过滤前，校验该分组确实属于当前分区+类型（跨区裸 id 一律 404）。 */
    public void requireGroupInScope(Long groupId, String bizType, DataScope scope) {
        requireBizType(bizType);
        requireGroup(groupId, bizType, DataPartition.forRequest(scope));
    }

    // ========== 内部 ==========

    private Map<Long, Integer> countByGroup(String bizType, DataPartition partition, List<Long> groupIds) {
        Set<Long> liveFileIds = fileObjectMapper.selectList(fileQuery(bizType, partition)
                        .select(FileObjectDO::getId)).stream()
                .map(FileObjectDO::getId).collect(Collectors.toSet());
        Map<Long, Integer> counts = new HashMap<>();
        if (liveFileIds.isEmpty() || groupIds.isEmpty()) {
            return counts;
        }
        relMapper.selectList(new LambdaQueryWrapper<FileGroupRelDO>()
                        .in(FileGroupRelDO::getGroupId, groupIds)).stream()
                .filter(rel -> liveFileIds.contains(rel.getFileId()))
                .forEach(rel -> counts.merge(rel.getGroupId(), 1, Integer::sum));
        return counts;
    }

    private Set<Long> liveGroupIds(String bizType, DataPartition partition) {
        return groupMapper.selectList(partitionQuery(bizType, partition).select(FileGroupDO::getId)).stream()
                .map(FileGroupDO::getId).collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<FileGroupDO> partitionQuery(String bizType, DataPartition partition) {
        return new LambdaQueryWrapper<FileGroupDO>()
                .eq(FileGroupDO::getBizType, bizType)
                .eq(FileGroupDO::getScope, partition.scope())
                .eq(FileGroupDO::getOwnerId, partition.ownerId());
    }

    private LambdaQueryWrapper<FileObjectDO> fileQuery(String bizType, DataPartition partition) {
        return new LambdaQueryWrapper<FileObjectDO>()
                .eq(FileObjectDO::getBizType, bizType)
                .eq(FileObjectDO::getScope, partition.scope())
                .eq(FileObjectDO::getOwnerId, partition.ownerId())
                .eq(FileObjectDO::getDeleted, 0);
    }

    private FileGroupDO requireGroup(Long id, String bizType, DataPartition partition) {
        FileGroupDO group = id == null ? null
                : groupMapper.selectOne(partitionQuery(bizType, partition).eq(FileGroupDO::getId, id));
        if (group == null) {
            throw BizException.notFound(ErrorCode.FILE_GROUP_NOT_FOUND, "分组不存在");
        }
        return group;
    }

    private void requireFile(Long fileId, String bizType, DataPartition partition) {
        FileObjectDO file = fileId == null ? null
                : fileObjectMapper.selectOne(fileQuery(bizType, partition).eq(FileObjectDO::getId, fileId));
        if (file == null) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "文件不存在或已被删除");
        }
    }

    private void requireGroupsInPartition(List<Long> groupIds, String bizType, DataPartition partition) {
        if (groupIds.isEmpty()) {
            return;
        }
        long matched = groupMapper.selectCount(partitionQuery(bizType, partition)
                .in(FileGroupDO::getId, groupIds));
        if (matched != groupIds.size()) {
            throw BizException.notFound(ErrorCode.FILE_GROUP_NOT_FOUND, "分组不存在或不属于当前范围");
        }
    }

    private void requireNameUnused(String bizType, String name, Long excludeId, DataPartition partition) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "分组名不能为空");
        }
        LambdaQueryWrapper<FileGroupDO> wrapper = partitionQuery(bizType, partition)
                .apply("TRIM(name) = {0}", name)
                .ne(excludeId != null, FileGroupDO::getId, excludeId);
        if (groupMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.FILE_GROUP_NAME_DUPLICATED, "当前范围已有同名分组");
        }
    }

    private static List<Long> normalize(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return groupIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static void requireBizType(String bizType) {
        if (!BIZ_DOCUMENT.equals(bizType) && !BIZ_VIDEO.equals(bizType)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未知的分组类型");
        }
    }

    @Data
    public static class SortItem {
        private Long id;
        private Integer sort;
    }
}
