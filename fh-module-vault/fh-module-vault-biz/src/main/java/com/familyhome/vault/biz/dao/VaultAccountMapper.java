package com.familyhome.vault.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
     * <p>这里直接带 {@code deleted = 0}：软删过的账号不该还能 reveal 出口令。
     *
     * @return 密文；记录不存在或已删除时返回 null
     */
    @Select("SELECT password_enc FROM vault_account WHERE id = #{id} AND deleted = 0")
    String selectPasswordEncById(@Param("id") Long id);
}
