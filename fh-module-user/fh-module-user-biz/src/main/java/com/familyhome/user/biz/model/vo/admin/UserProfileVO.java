package com.familyhome.user.biz.model.vo.admin;

import lombok.Getter;
import lombok.Setter;

/**
 * 个人中心的视图：只有"我自己的这几样"（昵称、手机号、头像）。
 *
 * <p><b>为什么不复用 {@link UserBriefVO}（登录/me 那个）加一个 phone</b>：brief 是要写进本机
 * localStorage 长期缓存的一份（见 shared/auth 的注释：刻意只放展示要用的四个字段），
 * 而手机号只在人主动进个人中心时需要一次。为了省一个接口把手机号塞进那一格，
 * 等于让 C 端每台设备本机都多一份家人号码——省不动。
 *
 * <p><b>为什么不复用 {@link UserAdminVO}</b>：那是"管理员看别人"的整行（带角色、创建/更新时间），
 * 只有 ADMIN 拿得到，而这一条普通成员也要用。两个视图各留一份，比把权限判据做成"字段级"清楚。
 *
 * <p><b>比 brief 多一格 {@code avatarFileId}</b>：brief 只给渲染用的 {@code avatarUrl}，
 * 而这一页要"换头像"——保存时得把当前这张的 id 原样带回（没换就不动那一列），
 * 所以这里额外给 id。url 仍优先缩略图，取不到就 null，前端画默认剪影。
 *
 * <p>没有口令、更没有哈希：这一页改口令走的是"原密码 + 新密码"那条接口，不需要读回任何东西。
 */
@Getter
@Setter
public class UserProfileVO {

    private Long id;

    private String name;

    /** 手机号，明文（与库里一致，个人资料项） */
    private String phone;

    /** 当前头像的文件 id；null = 还没设过头像。保存时原样带回，服务端 null 就不改这一列 */
    private Long avatarFileId;

    /** 当前头像的访问地址（优先缩略图）；null 时前端画默认剪影 */
    private String avatarUrl;
}
