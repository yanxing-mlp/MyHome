package com.familyhome.user.biz.controller.c;

import com.familyhome.common.result.Result;
import com.familyhome.user.biz.model.request.UserLoginRequest;
import com.familyhome.user.biz.model.vo.admin.UserBriefVO;
import com.familyhome.user.biz.model.vo.admin.UserOptionVO;
import com.familyhome.user.biz.service.AppUserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号 C 端接口（方案 §5.4）：登录页要的两条 + 开机自校的一条。
 *
 * <p>只有这三条。<b>账号管理（增删改查）与个人中心（{@code /profile} 那三条）都不在 C 端，
 * 连路径都不给</b>：前者是 B 端 ADMIN 的事，后者是 h5 没有那一页（C 端只有首页一张"当前是谁"卡）。
 * 服务端 {@code AppUserService} 里对账号管理还有第二道 {@code requireAdmin()}。
 *
 * <p>口令哈希从来不经这三条中的任何一条返回：{@code /options} 只有 id + 昵称，
 * {@code /login} 与 {@code /me} 返回的是 {@code UserBriefVO}（id/昵称/角色/头像），
 * 其中<b>只有 {@code /login} 多带一格服务端签发的登录令牌</b>（{@code /me} 恒为 null）。
 * C 端改口令因此没有路径——要改口令得上 B 端登录一次（v8 口径，见方案 §5.3）。
 *
 * <p>{@code /options} 同时是两端"添加人/下单人"的 id → 昵称字典（刻意不给手机号），
 * 所以 C 端下单页与订单列表读的是这里这一条。
 *
 * <p>三条与 B 端同名接口共用同一套 service：{@code /options} 与 {@code /login} 本来就不要求身份
 * （登录页此刻还没有人），{@code /me} 读的是请求头 {@code Authorization: Bearer <token>}，
 * 拦截器验签后只认人、不拦人。
 */
@Validated
@RestController
@RequestMapping("/api/c/user")
@RequiredArgsConstructor
public class UserCController {

    private final AppUserService appUserService;

    /** 登录页的下拉框数据；也是 C 端"谁下的单"那份昵称字典。后端刻意不给手机号。 */
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options() {
        return Result.ok(appUserService.options());
    }

    /** 下拉选账号 + 填密码。成功后前端把返回的登录令牌写进 localStorage。 */
    @PostMapping("/login")
    public Result<UserBriefVO> login(@Valid @RequestBody UserLoginRequest request) {
        return Result.ok(appUserService.login(request));
    }

    /** 用本机令牌换回"还在不在 + 现在的昵称/头像/角色"；令牌失效或账号没了返回 401。 */
    @GetMapping("/me")
    public Result<UserBriefVO> me() {
        return Result.ok(appUserService.me());
    }
}
