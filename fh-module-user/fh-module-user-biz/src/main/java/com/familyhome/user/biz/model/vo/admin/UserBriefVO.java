package com.familyhome.user.biz.model.vo.admin;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 当前登录者的视图，{@code POST /login} 成功与 {@code GET /me} 都返回它。
 *
 * <p>前端把 {@code id}/{@code name}/{@code role}/{@code avatarUrl}/{@code permissions} 存本机用来展示
 * "现在是谁"、决定各菜单可不可见、各业务页是不是只读；{@code role} 与 {@code permissions} 都只给前端
 * 露不露菜单用，<b>真正的权限判据在服务端</b>（令牌里不含 role，拦截器每次都从库里取现值再逐接口
 * {@code requirePermission}）。前端藏菜单只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p><b>{@code permissions} 是 v48 三档角色的落点</b>：这一格是该角色当前勾选的权限码清单
 * （超管恒为全量）。前端 {@code can(code)} 就读它。改矩阵/改角色后，下一次 {@code /me} 校准就带新值，
 * 与"服务端逐请求现查"是同一份数据，不会各说各话。
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

    /** SUPER_ADMIN / ADMIN / MEMBER，口径见 {@code CurrentUserHolder} */
    private String role;

    private String avatarUrl;

    /**
     * 该角色当前勾选的权限码清单（超管恒为全量）。前端 {@code can(code)} 读它决定菜单/只读态。
     * 与服务端 {@code requirePermission} 用的是同一份 {@code role_permission} 现值，见类注释。
     */
    private List<String> permissions;

    /** 登录令牌，<b>仅 {@code /login} 返回</b>；{@code /me} 恒为 null。见类注释。 */
    private String token;
}
