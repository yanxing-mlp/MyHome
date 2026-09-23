package com.familyhome.album.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.album.biz.entity.AlbumCityDO;
import com.familyhome.album.biz.entity.AlbumGroupDO;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import java.util.Objects;

/** 相册数据分区：家庭固定 owner=0，个人以当前账号为 owner；上传人与属主分开存储。 */
public record AlbumPartition(AlbumScope scope, Long ownerId) {

    public AlbumPartition {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(ownerId);
        if (scope == AlbumScope.FAMILY && ownerId != 0L
                || scope == AlbumScope.PERSONAL && ownerId <= 0L) {
            throw new IllegalArgumentException("无效的相册分区");
        }
    }

    public static AlbumScope defaultScope(AlbumScope scope) {
        return scope == null ? AlbumScope.FAMILY : scope;
    }

    /** 列表及主动重算的个人档缺身份返回 401。 */
    public static AlbumPartition forRequest(AlbumScope scope) {
        AlbumScope effective = defaultScope(scope);
        return new AlbumPartition(effective,
                effective == AlbumScope.FAMILY ? 0L : CurrentUserHolder.requireUserId());
    }

    public static AlbumPartition of(AlbumGroupDO group) {
        return new AlbumPartition(group.getScope(),
                group.getScope() == AlbumScope.FAMILY ? 0L : group.getCreatorId());
    }

    public static AlbumPartition of(AlbumImageDO image) {
        return new AlbumPartition(image.getScope(), image.getOwnerId());
    }

    public boolean contains(AlbumImageDO image) {
        return scope == image.getScope() && ownerId.equals(image.getOwnerId());
    }

    public LambdaQueryWrapper<AlbumGroupDO> groups() {
        return new LambdaQueryWrapper<AlbumGroupDO>()
                .eq(AlbumGroupDO::getScope, scope)
                .eq(scope == AlbumScope.PERSONAL, AlbumGroupDO::getCreatorId, ownerId)
                .ne(AlbumGroupDO::getStatus, ContentStatus.DELETED);
    }

    public LambdaQueryWrapper<AlbumImageDO> images() {
        return new LambdaQueryWrapper<AlbumImageDO>()
                .eq(AlbumImageDO::getScope, scope)
                .eq(AlbumImageDO::getOwnerId, ownerId)
                .ne(AlbumImageDO::getStatus, ContentStatus.DELETED);
    }

    public LambdaQueryWrapper<AlbumCityDO> cities() {
        return new LambdaQueryWrapper<AlbumCityDO>()
                .eq(AlbumCityDO::getScope, scope)
                .eq(AlbumCityDO::getOwnerId, ownerId);
    }
}
