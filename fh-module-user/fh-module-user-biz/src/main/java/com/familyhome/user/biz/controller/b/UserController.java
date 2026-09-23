package com.familyhome.user.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.user.biz.model.request.UserCreateRequest;
import com.familyhome.user.biz.model.request.UserLoginRequest;
import com.familyhome.user.biz.model.request.UserPasswordUpdateRequest;
import com.familyhome.user.biz.model.request.UserProfileUpdateRequest;
import com.familyhome.user.biz.model.request.UserRoleUpdateRequest;
import com.familyhome.user.biz.model.vo.admin.UserAdminVO;
import com.familyhome.user.biz.model.vo.admin.UserBriefVO;
import com.familyhome.user.biz.model.vo.admin.UserOptionVO;
import com.familyhome.user.biz.model.vo.admin.UserProfileVO;
import com.familyhome.user.biz.service.AppUserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号 B 端接口（登录 + 当前用户 + 个人中心 + 账号管理）。
 *
 * <p>C 端要的那三条（{@code /options}、{@code /login}、{@code /me}）在
 * {@code /api/c/user/**} 有一条同名的（{@code UserCController}），共用同一套 service：
 * 两端各走各的前缀，部署层才能按前缀分网段放行（方案 §8.3）。下面这三条留着，
 * 是因为 B 端自己也用同一份昵称字典与同一个登录页。
 *
 * <p><b>个人中心那三条（{@code /profile} 系列）与账号管理那四条（列表/新建/改角色/删除）只有 B 端有</b>，
 * C 端连路径都不给：h5 没有这一页（C 端只有首页那张"当前是谁"卡），改口令的路径因此也只有一条——
 * 家人要用手机改口令，就得上 B 端登一次。这是"谁有调用方才给哪条路径"那条判据的直接结果，
 * 将来真要加 {@code /api/c/user/profile}，共用的是同一个 service 方法，别再写一份实现。
 *
 * <p><b>这里没有"把整行改掉"的 {@code PUT /{id}}</b>：管理员对别人的行只有三种动作——加一个、删一个、
 * 定他是超管还是成员。后那一种走 {@code PUT /{id}/role}，一条接口只写 {@code role} 一列；
 * 昵称/手机号/头像/口令四项都在个人中心那一侧，由本人改。所以这个类里带路径参数的写接口一共两条
 * （{@code /{id}/role} 与 {@code /{id}}），都不收口令，管理员也从这里换不到任何人的口令
 * （{@code UserAdminVO} 里没有口令字段，{@code AppUserDO.passwordHash} 又被标了 {@code select = false}，
 * 压根查不出来）。
 *
 * <p><b>登录与下拉候选这两条不要求身份</b>：{@code /options} 与 {@code /login} 此刻还没有人。
 * {@code /me} 是"用请求头换回现在的自己"，必须在拦截器之后。个人中心那三条同样只要身份、
 * 不要 ADMIN（改的是自己），账号管理那四条才在服务层再判一次 {@code requireAdmin()}。
 *
 * <p>权限判断<b>没有</b>写在拦截器里：拦截器只负责"你是谁"，"这个接口要什么角色"留在
 * {@code AppUserService} 的 {@code requireAdmin()} 上，这样扫一遍 service 就能看清哪些接口敏感。
 */
@Validated
@RestController
@RequestMapping("/api/b/user")
@RequiredArgsConstructor
public class UserController {

    private final AppUserService appUserService;

    /** 登录页的下拉框数据；同时是两端"添加人"列的 id → 昵称字典。 */
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options() {
        return Result.ok(appUserService.options());
    }

    /** 下拉选人 + 填密码。成功后前端把返回的登录令牌写进 localStorage。 */
    @PostMapping("/login")
    public Result<UserBriefVO> login(@Valid @RequestBody UserLoginRequest request) {
        return Result.ok(appUserService.login(request));
    }

    /** 用本机令牌换回"还在不在 + 现在的昵称/头像/角色"；令牌失效或账号没了返回 401。 */
    @GetMapping("/me")
    public Result<UserBriefVO> me() {
        return Result.ok(appUserService.me());
    }

    /** 个人中心显示用：自己的昵称与手机号。手机号不在 {@code /me} 里，所以单独要一次。 */
    @GetMapping("/profile")
    public Result<UserProfileVO> profile() {
        return Result.ok(appUserService.profile());
    }

    /** 个人中心改自己的昵称/手机号。没有 role、没有 password，改不了别人的行。 */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody UserProfileUpdateRequest request) {
        appUserService.updateProfile(request);
        return Result.ok();
    }

    /** 个人中心改自己的口令（原密码 + 新密码）。失败只有"原密码不正确"一句，不区分账号状态。 */
    @PutMapping("/profile/password")
    public Result<Void> updatePassword(@Valid @RequestBody UserPasswordUpdateRequest request) {
        appUserService.updatePassword(request);
        return Result.ok();
    }

    @GetMapping
    public Result<List<UserAdminVO>> list() {
        return Result.ok(appUserService.list());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(appUserService.create(request));
    }

    /**
     * 设置某个人的角色（ADMIN / MEMBER）。<b>管理员替别人写东西的口子只有这一条，且只写 {@code role} 一列</b>：
     * 昵称/手机号/口令/头像仍然只有本人能在上面那三条 {@code /profile*} 里改（头像建号时管理员先定一张，之后本人自己换）。
     * 后端挡住"改自己"和"降掉最后一个管理员"（{@code AppUserService#updateRole}）。
     */
    @PutMapping("/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id, @Valid @RequestBody UserRoleUpdateRequest request) {
        appUserService.updateRole(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appUserService.delete(id);
        return Result.ok();
    }
}
