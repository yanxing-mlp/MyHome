package com.familyhome.common.context;

import com.familyhome.common.enums.DataScope;
import java.util.Objects;

/** 公共数据 owner=0，私人数据 owner=当前账号；所有密码本/文档请求均要求登录身份。 */
public record DataPartition(DataScope scope, Long ownerId) {
    public DataPartition {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(ownerId);
        if (scope == DataScope.PUBLIC && ownerId != 0L
                || scope == DataScope.PRIVATE && ownerId <= 0L) {
            throw new IllegalArgumentException("无效的数据分区");
        }
    }

    public static DataPartition forRequest(DataScope scope) {
        Long userId = CurrentUserHolder.requireUserId();
        DataScope effective = DataScope.orPublic(scope);
        return new DataPartition(effective, effective == DataScope.PUBLIC ? 0L : userId);
    }

    public boolean contains(DataScope actualScope, Long actualOwnerId) {
        return scope == actualScope && ownerId.equals(actualOwnerId);
    }
}
