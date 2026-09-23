package com.familyhome.user.biz.model.vo.admin;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号管理页的一行（只有 ADMIN 拿得到，见 {@code AppUserService#list}）。
 *
 * <p><b>不返回 {@code creatorId}</b>：本期"添加人"列的口径是用户点名的那 8 处
 * （订单/图片/分组/菜品/分类/文件/密码本/C 端下单人），账号管理页不在其中——
 * 列表里能加账号的人本来就只有管理员一个，加了也是一列 constant。
 * {@code app_user} 表里也就没有这一列（见 {@code AppUserDO}），不存没人读的东西。
 */
@Getter
@Setter
public class UserAdminVO {

    private Long id;

    private String name;

    private String phone;

    /** ADMIN / MEMBER */
    private String role;

    /** null = 前端展示默认头像 */
    private String avatarUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
