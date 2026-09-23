package com.familyhome.user.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.biz.entity.AppUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账号 Mapper。
 *
 * <p>只有一条自定义 SQL：取口令哈希。实体上那一列标了 {@code select = false}，
 * BaseMapper 的所有方法都不会带出它，所以登录校验只能走这条显式 SQL
 * （与 {@code VaultAccountMapper#selectPasswordEncById} 同一手法、同一个理由——
 * 让"凭据不出 DAO"成为框架行为，而不是每次 review 时靠人记住）。
 *
 * <p>其余查询（判重 / 取全部 / 列表）都是服务层用 LambdaQueryWrapper 表达的简单查询，
 * {@code @TableLogic} 已经把"别取已删账号"交给框架，不必再写 SQL。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUserDO> {

    /**
     * 取出某个账号的口令哈希。
     *
     * <p>这里直接带 {@code deleted = 0}：软删掉的账号不该还能被拿来验口令。
     * 与 {@code selectById} 不同，这一条只回一列，调用方拿不到昵称/角色，
     * 所以"账号还在不在"仍由 service 里那次 {@code selectById} 判（顺序见
     * {@code AppUserService#login}）。
     *
     * @return 哈希串；账号不存在或已删除时返回 null
     */
    @Select("SELECT password_hash FROM app_user WHERE id = #{id} AND deleted = 0")
    String selectPasswordHashById(@Param("id") Long id);
}
