package com.familyhome.album.biz.service;

import com.familyhome.album.biz.dao.AlbumGroupMapper;
import com.familyhome.album.biz.dao.AlbumImageMapper;
import com.familyhome.album.biz.entity.AlbumGroupDO;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** B/C 共用裸 ID 校验：跨 scope、跨个人属主、已删及无身份私人访问统一 404。 */
@Service
@RequiredArgsConstructor
public class AlbumAccessService {

    private final AlbumGroupMapper groupMapper;
    private final AlbumImageMapper imageMapper;

    public AlbumGroupDO requireGroup(Long id, AlbumScope scope, boolean onShelfOnly) {
        return validateGroup(id == null ? null : groupMapper.selectById(id), scope, onShelfOnly);
    }

    /** 组绑定/删除/关系覆盖先锁组，再锁文件；必须校验锁等待后的当前状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public AlbumGroupDO requireGroupForUpdate(Long id, AlbumScope scope, boolean onShelfOnly) {
        return validateGroup(id == null ? null : groupMapper.selectByIdForUpdate(id), scope, onShelfOnly);
    }

    private AlbumGroupDO validateGroup(AlbumGroupDO group, AlbumScope scope, boolean onShelfOnly) {
        if (group == null || group.getStatus() == ContentStatus.DELETED
                || onShelfOnly && group.getStatus() != ContentStatus.ON_SHELF
                || !canAccess(scope, group.getScope(),
                        group.getScope() == AlbumScope.FAMILY ? 0L : group.getCreatorId())) {
            throw BizException.notFound(ErrorCode.ALBUM_GROUP_NOT_FOUND, "分组不存在");
        }
        return group;
    }

    public AlbumImageDO requireImage(Long id, AlbumScope scope) {
        AlbumImageDO image = id == null ? null : imageMapper.selectById(id);
        if (image == null || image.getStatus() == ContentStatus.DELETED
                || !canAccess(scope, image.getScope(), image.getOwnerId())) {
            throw BizException.notFound(ErrorCode.ALBUM_IMAGE_NOT_FOUND, "图片不存在");
        }
        return image;
    }

    private boolean canAccess(AlbumScope requested, AlbumScope actual, Long ownerId) {
        if (AlbumPartition.defaultScope(requested) != actual) {
            return false;
        }
        if (actual == AlbumScope.FAMILY) {
            return Long.valueOf(0).equals(ownerId);
        }
        var user = CurrentUserHolder.get();
        return user != null && user.id() != null && user.id().equals(ownerId);
    }

    /** 删除只能走删除链路，不能借通用更新绕过关系清理和文件引用保护。 */
    public static void requireShelfStatus(ContentStatus status) {
        if (status != null && status != ContentStatus.ON_SHELF && status != ContentStatus.OFF_SHELF) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "状态只允许 ON_SHELF 或 OFF_SHELF");
        }
    }
}
