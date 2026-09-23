package com.familyhome.vault.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.common.enums.DataScope;
import com.familyhome.vault.biz.entity.VaultAccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VaultAccountMapper extends BaseMapper<VaultAccountDO> {

    /**
     * 取出某条记录的口令密文。
     *
     * <p>实体上 {@code passwordEnc} 标了 {@code select = false}，BaseMapper 的所有方法都不会
     * 带出这一列，所以 reveal 只能走这条显式 SQL。刻意<b>不</b>把密文塞进 {@code VaultAccountDO}
     * 再返回——那样密文就会在 service/controller 之间随手可传，泄露面立刻扩大。
     *
     * <p>显式限定 scope、owner_id 和 {@code deleted = 0}，不能仅凭 ID 读取其他分区的口令。
     * 分区参数只能由服务端根据当前登录身份解析。
     *
     * @return 密文；记录不在当前分区、不存在或已删除时返回 null
     */
    @Select("SELECT password_enc FROM vault_account WHERE id = #{id}"
            + " AND scope = #{scope} AND owner_id = #{ownerId} AND deleted = 0")
    String selectPasswordEncById(@Param("id") Long id, @Param("scope") DataScope scope,
                                @Param("ownerId") Long ownerId);
}
