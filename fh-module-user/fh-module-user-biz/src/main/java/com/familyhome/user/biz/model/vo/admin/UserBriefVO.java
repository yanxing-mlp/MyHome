package com.familyhome.user.biz.model.vo.admin;

import lombok.Getter;
import lombok.Setter;

/**
 * 当前登录者的视图，{@code POST /login} 成功与 {@code GET /me} 都返回它。
 *
 * <p>前端把 {@code id}/{@code name}/{@code role}/{@code avatarUrl} 存本机用来展示"现在是谁"、
 * 决定"账号管理"菜单可不可见；{@code role} 只是给前端露不露菜单用，<b>真正的权限判据在服务端</b>
 * （令牌里不含 role，拦截器每次都从库里取现值）。
 *
 * <p><b>{@code token} 只在 {@code /login} 里有值</b>：那是服务端签出来、前端之后每个请求放进
 * {@code Authorization: Bearer} 的登录令牌（取代了旧那个可手搓的 {@code X-User-Id}）。
 * {@code /me} 返回的这一格恒为 null——它用当前令牌就能调，不需要、也不应该再下发一枚新令牌，
 * 前端的 {@code refreshMe()} 只覆盖展示字段、不碰本机存的令牌。
 *
 * <p><b>没有 phone</b>：手机号只在登录那一刻由用户自己输入，展示时不需要，C 端更不该看到别人的号码。
 * {@code avatarUrl} 为 null 时前端用 {@code shared/image} 的默认头像，不在服务端拼字符串。
 */
@Getter
@Setter
public class UserBriefVO {

    private Long id;

    private String name;

    /** ADMIN / MEMBER，口径见 {@code CurrentUserHolder} */
    private String role;

    private String avatarUrl;

    /** 登录令牌，<b>仅 {@code /login} 返回</b>；{@code /me} 恒为 null。见类注释。 */
    private String token;
}
