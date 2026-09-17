package com.familyhome.vault.biz.converter;

import com.familyhome.vault.biz.entity.VaultAccountDO;
import com.familyhome.vault.biz.model.vo.admin.VaultAccountAdminVO;

/**
 * 手写转换，不引 MapStruct（方案 §3.3）。
 *
 * <p>DO -> VO 是<b>白名单式逐字段赋值</b>，不是 BeanUtils.copyProperties：
 * 拷贝工具会把将来新增的敏感字段一起带出去，这里是显式的，加字段时必须手动过一遍。
 */
public final class VaultAccountConverter {

    private VaultAccountConverter() {
    }

    public static VaultAccountAdminVO toAdminVO(VaultAccountDO source) {
        if (source == null) {
            return null;
        }
        VaultAccountAdminVO vo = new VaultAccountAdminVO();
        vo.setId(source.getId());
        vo.setName(source.getName());
        vo.setAccount(source.getAccount());
        vo.setCreateTime(source.getCreateTime());
        vo.setUpdateTime(source.getUpdateTime());
        return vo;
    }
}
