package com.familyhome.user.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.user.biz.entity.RolePermissionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色↔权限勾选 Mapper。
 *
 * <p>没有自定义 SQL：读某角色的权限、按角色整批删、逐行插，全都是 {@code LambdaQueryWrapper} +
 * BaseMapper 能表达的简单操作，交给 {@code RolePermissionService} 组织即可
 * （与 {@code RecipeCategoryRelMapper} / {@code FileGroupRelMapper} 同一手法）。
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionDO> {
}
