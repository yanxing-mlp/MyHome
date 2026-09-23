package com.familyhome.user.biz.model.vo.admin;

import lombok.Getter;
import lombok.Setter;

/**
 * 账号选项：登录页下拉框 + 各页"添加人"的 id → 昵称字典。
 *
 * <p>两端各有一条 {@code /options}（B 端 {@code GET /api/b/user/options}、C 端 {@code GET /api/c/user/options}），
 * 都<b>不要求身份</b>（登录前就要能拉列表），所以这里
 * <b>只有 id 和昵称</b>：手机号、角色、头像都不给。两端拿到它做纯展示，
 * 昵称的解析因此在服务端完全不需要跨域依赖（与"做法名由前端字典解析"同一口径）。
 */
@Getter
@Setter
public class UserOptionVO {

    private Long id;

    private String name;
}
