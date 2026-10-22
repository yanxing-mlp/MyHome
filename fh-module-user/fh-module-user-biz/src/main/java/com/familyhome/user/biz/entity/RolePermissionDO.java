package com.familyhome.user.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 角色↔权限勾选，表 {@code role_permission}（v48）。
 *
 * <p>一行 = "某个角色勾了某个权限码"。整张表就是「角色管理」页那张可编辑矩阵的落库形态：
 * 改矩阵 = 按角色整批删了重插（{@code RolePermissionService#save}），靠 {@code uk_role_permission}
 * 兜并发重复。角色固定三种、权限码也是 {@code PermissionCatalog} 里写死的一批，可编辑的只有"谁勾了哪些"。
 *
 * <p><b>刻意不落 SUPER_ADMIN 的行</b>：超管拥有全部权限、由 {@code CurrentUserHolder.hasPermission}
 * 短路，与这张表无关（防自锁）。所以本表只可能出现 ADMIN / MEMBER 两档。
 *
 * <p>纯配置关联表，无 {@code deleted} 列（与 {@code recipe_category_rel} / {@code file_group_rel} 同构），
 * 但 create_time / update_time 两列照建表规范都留。
 */
@Data
@TableName("role_permission")
public class RolePermissionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色：ADMIN / MEMBER（SUPER_ADMIN 不落表） */
    private String role;

    /** 权限码，取值必须是 {@code PermissionCatalog} 里的合法项 */
    private String permission;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
