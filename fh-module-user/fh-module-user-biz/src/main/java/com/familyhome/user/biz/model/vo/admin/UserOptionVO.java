package com.familyhome.user.biz.model.vo.admin;

import lombok.Getter;
import lombok.Setter;

/**
 * 账号选项：登录页下拉框 + 各页"添加人"的 id → 昵称字典。
 *
 * <p>两端各有一条 {@code /options}（B 端 {@code GET /api/b/user/options}、C 端 {@code GET /api/c/user/options}），
 * 都<b>不要求身份</b>（登录前就要能拉列表），所以这里<b>只有 id、昵称和头像缩略图</b>：
 * 手机号、角色不给。头像是 2026-09-23 加进这份字典的——C 端购物车按加购人分模块后，
 * 模块头要画这个人的头像，而那份字典是 h5 唯一拿得到"别人长什么样"的地方；
 * 给的是缩略图 URL（与 {@code UserBriefVO} 同一口径），没传过头像就是 null、前端画默认剪影；
 * 头像 URL 由 service 里 {@code avatarUrls()} 一次性批量取，不在循环里调 {@code FileFacade}。
 */
@Getter
@Setter
public class UserOptionVO {

    private Long id;

    private String name;

    /** 头像缩略图 URL；没传过头像或文件已被删时为 null。 */
    private String avatarUrl;
}
