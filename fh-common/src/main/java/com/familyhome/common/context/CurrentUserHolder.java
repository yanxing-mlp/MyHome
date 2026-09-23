package com.familyhome.common.context;

import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;

/**
 * 当前登录用户（请求级 ThreadLocal）。
 *
 * <p><b>为什么放在 fh-common</b>：写"添加人"的地方分散在 album / recipe / file / vault 四个域，
 * 而它们之间唯一的公共依赖就是 fh-common（方案 §3 的依赖方向）。放任何一个业务域里，
 * 都会凭空多出一条"相册依赖账号"这种跨域边。
 *
 * <p><b>谁来写这一格</b>：只有 fh-boot 的 {@code CurrentUserInterceptor}——它读 {@code Authorization}
 * 头里的登录令牌、验签验有效期、再校验账号还在，然后 set，并在 {@code afterCompletion} 里 clear。
 * 业务代码只读不写。
 *
 * <p><b>为什么用 ThreadLocal 而不是把 userId 一路当参数传下去</b>：那要改七个 service 的签名
 * 和所有 controller 方法，而"这条记录是谁加的"本来就是请求上下文，不是业务入参——
 * 用户不该能在请求体里指定"帮我把添加人写成别人"。
 *
 * <p>异步/线程池场景下 ThreadLocal 不会自动带过去。本服务是同步 MVC，没有 @Async，
 * 将来若引入异步写库，必须在提交任务前把 {@link #get()} 显式带过去。
 */
public final class CurrentUserHolder {

    /**
     * 承载登录令牌的请求头（{@code Authorization: Bearer <token>}）。
     *
     * <p>取代了旧的 {@code X-User-Id}：那个头由客户端自报、服务端无条件相信，于是
     * {@code curl -H 'X-User-Id: 1'} 就能冒充大宝(ADMIN)——这就是这一版要堵的越权。
     * 现在头里装的是 {@link com.familyhome.common.auth.SessionToken} 签出来、改一个字符就验不过的令牌，
     * 由 fh-boot 的拦截器校验后才换成本线程的"当前登录人"。两端各自的 HTTP 层统一注入，业务代码不手写这个头。
     */
    public static final String AUTH_HEADER = "Authorization";

    /** {@code Authorization} 头里令牌的前缀；拦截器剥掉它、HTTP 层拼上它，两端一致。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 当前登录用户。
     *
     * @param id   app_user.id，写 creator_id 用
     * @param name 昵称，只用于日志
     * @param role ADMIN / MEMBER，账号管理的权限判据
     */
    public record CurrentUser(Long id, String name, String role) {

        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }
    }

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    /** 只给 fh-boot 的拦截器用；业务代码一律走 {@link #get()} / {@link #requireUserId()} */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 当前用户；拦截器没放行（白名单接口、或同步块之外的线程）时为 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户 ID，取不到就是"没登录就来写数据"。
     *
     * <p>各域新建记录时都调这一个方法，所以"添加人从哪来"全库只有一个答案。
     */
    public static Long requireUserId() {
        CurrentUser user = HOLDER.get();
        if (user == null || user.id() == null) {
            throw BizException.of(ErrorCode.USER_NOT_LOGIN, "请先选择登录账号");
        }
        return user.id();
    }

    /** 账号管理类接口才允许走；不在拦截器里判，是为了让"哪个接口要管理员"读代码时看得见 */
    public static CurrentUser requireAdmin() {
        CurrentUser user = HOLDER.get();
        if (user == null || user.id() == null) {
            throw BizException.of(ErrorCode.USER_NOT_LOGIN, "请先选择登录账号");
        }
        if (!user.isAdmin()) {
            throw BizException.of(ErrorCode.USER_FORBIDDEN, "只有管理员能管理账号");
        }
        return user;
    }

    /** 也只给拦截器用（afterCompletion 必须调，线程池复用线程时会串号） */
    public static void clear() {
        HOLDER.remove();
    }
}
