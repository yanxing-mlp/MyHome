package com.familyhome.boot.config;

import com.familyhome.common.auth.SessionToken;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.user.biz.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把请求头 {@code Authorization: Bearer <令牌>} 换成本线程的"当前登录人"。
 *
 * <p><b>只有这一个地方认身份</b>：七个写"添加人"的 service、账号管理的 {@code requireAdmin()}、
 * 私人分区的属主判据，全都只看 {@code CurrentUserHolder}，所以"这个人是谁、凭什么"在代码里只有一个答案。
 *
 * <p><b>这一版修掉的越权</b>：旧实现读的是客户端自报的 {@code X-User-Id}，服务端只查"这个 id 还在不在"，
 * 于是任何人 {@code curl -H 'X-User-Id: 1'} 就成了大宝(ADMIN)——能列全家账号(含手机号)、建号删号、
 * 改角色、reveal 密码本、读写别人的私人分区，登录时那次口令核对形同虚设。现在头里装的是
 * {@link SessionToken} 用服务端密钥签出来、带 7 天有效期、改一个字符就验不过的令牌：
 * <b>验签通过</b>才拿令牌里的 userId 去 {@link AppUserService#resolve} 换现值（name/role 从库里取，
 * 所以改角色、删号下一次请求立即生效），验不过一律 401。
 *
 * <p><b>头缺失时仍放行（当作匿名）</b>：一期只要求"写数据必须知道自己是谁"，读接口的网络暴露面
 * 仍由部署层的 nginx 内网白名单兜（方案 §8.3）。于是这一层不需要白名单列表——{@code /login}、
 * {@code /options} 本来就不带令牌。"没登录就写"由 {@code CurrentUserHolder.requireUserId()} 抛 401，
 * 判定点离写库更近。<b>注意"匿名放行"只针对"根本没带头"</b>：带了一枚假令牌不是匿名，是 401。
 *
 * <p><b>令牌无效/过期</b>：{@link SessionToken#verify} 抛 {@code USER_NOT_LOGIN}（401），
 * 前端 HTTP 层收到 401 就清掉本机登录态回登录页——这与"账号被删"（{@code resolve} 抛 {@code USER_NOT_FOUND}，
 * 也是 401）走同一个自愈分支。
 *
 * <p>{@code afterCompletion} 必须清 ThreadLocal：Tomcat 的工作线程是复用的，
 * 不清就会下一个请求顶着上一个用户的身份写库。它对所有进入 {@link #preHandle} 的请求都会回调
 * （包括 preHandle 抛异常的情况），所以 clear 放在这里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentUserInterceptor implements HandlerInterceptor {

    private final AppUserService appUserService;
    private final SessionToken sessionToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(CurrentUserHolder.AUTH_HEADER);
        if (!StringUtils.hasText(header)) {
            // 没带 Authorization：匿名放行，需要身份的接口由 requireUserId()/requireAdmin() 自己挡
            return true;
        }
        String trimmed = header.trim();
        if (!trimmed.startsWith(CurrentUserHolder.BEARER_PREFIX)) {
            // 带了 Authorization 却不是 Bearer 方案：不认，按匿名处理（与"没带头"等价），
            // 不值得为它单设一条错误码；真要在这一档较真，较真的是部署层的白名单。
            log.warn("忽略非 Bearer 的 {} 请求头: uri={}", CurrentUserHolder.AUTH_HEADER, request.getRequestURI());
            return true;
        }
        String token = trimmed.substring(CurrentUserHolder.BEARER_PREFIX.length()).trim();
        // verify 内部对"空/格式烂/签名不对/过期"一律抛 USER_NOT_LOGIN(401)，不把令牌原文带进异常或日志
        long userId = sessionToken.verify(token);
        CurrentUserHolder.set(appUserService.resolve(userId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        CurrentUserHolder.clear();
    }
}
