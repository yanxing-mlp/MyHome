package com.familyhome.user.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员设置某个人的角色：只有 {@code ADMIN} / {@code MEMBER} 两个值，取值与
 * {@code CurrentUserHolder.ROLE_ADMIN} / {@code ROLE_MEMBER} 逐字对齐（合法范围在
 * {@code AppUserService#updateRole} 里判，那里同时判"改的是不是自己"）。
 *
 * <p><b>这是管理员唯一能替别人写的一格</b>：整条接口只更新 {@code app_user.role} 这一列，
 * 昵称、手机号、口令、头像都不在这条的入参里（那四项仍然只有本人能在个人中心动）。
 * 它买到的是"谁能进账号管理这一页"，买不到任何人的凭据。
 *
 * <p>为什么单独开一条 {@code PUT /{id}/role} 而不是恢复成一条 {@code PUT /{id}}：
 * 后者是个"什么都能改"的形状，早先正是它带上了"管理员替别人重置口令"。窄到只剩一个字段之后，
 * 想越界得先改这个类，而改这个类会在 review 里非常显眼。
 */
@Data
public class UserRoleUpdateRequest {

    @NotBlank(message = "请选择角色")
    private String role;
}
