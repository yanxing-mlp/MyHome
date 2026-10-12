package com.familyhome.vault.biz.converter;

import com.familyhome.vault.biz.entity.VaultAccountDO;
import com.familyhome.vault.biz.model.vo.admin.VaultAccountAdminVO;
import com.familyhome.vault.biz.model.vo.client.VaultAccountClientVO;

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
        vo.setScope(source.getScope());
        vo.setOwnerId(source.getOwnerId());
        vo.setCreatorId(source.getCreatorId());
        vo.setCreateTime(source.getCreateTime());
        vo.setUpdateTime(source.getUpdateTime());
        return vo;
    }

    /**
     * DO -> C 端只读视图。同样是白名单式逐字段赋值：只带 id / name / account 三列，
     * 分区、属主、添加人、时间都不下发，口令更是压根不在这个 VO 上（走 reveal 单独取）。
     */
    public static VaultAccountClientVO toClientVO(VaultAccountDO source) {
        if (source == null) {
            return null;
        }
        VaultAccountClientVO vo = new VaultAccountClientVO();
        vo.setId(source.getId());
        vo.setName(source.getName());
        vo.setAccount(source.getAccount());
        return vo;
    }
}
