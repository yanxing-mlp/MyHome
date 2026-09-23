package com.familyhome.user.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人中心改自己的资料：昵称、手机号、头像三项。
 *
 * <p><b>一处刻意没有的字段</b>：没有 {@code password}——改口令是另一条接口
 * （{@link UserPasswordUpdateRequest}），它要额外核对原密码，失败原因和"资料没保存"是两回事，
 * 揉在一个表单里会让"到底是哪一格没通过"变成一句含糊的报错。
 *
 * <p><b>{@code avatarFileId} 为空 = 不改头像</b>：头像是"先上传拿到 fileId、再随保存提交"的两步，
 * 上传那一刻文件已经落到存储里，但只有点了保存才把这个 id 写进账号。服务端走 MyBatis-Plus 的
 * {@code updateById}（默认 {@code NOT_NULL} 策略），null 的那一列根本不进 UPDATE，
 * 所以"没传新头像"与"保持原样"是同一件事——这条也因此<b>只能换头像、不能清空头像</b>
 * （想把头像恢复成默认剪影目前没有入口，与"管理员也不替别人清头像"同一口径）。
 * 前端提交的是当前 state 里那个 id（没动过时就是 profile 带回来的原 id），不会传 null。
 *
 * <p>也没有 {@code role}：这一条是"自己改自己"、只要求登录态，一个能写角色的入参等于让任何人都能把
 * 自己提成管理员。提权那条路只有一条、且判 {@code requireAdmin()}（见 {@link UserRoleUpdateRequest}）。
 */
@Data
public class UserProfileUpdateRequest {

    @NotBlank(message = "请输入昵称")
    @Size(max = 32, message = "昵称最多 32 个字符")
    private String name;

    @NotBlank(message = "请输入手机号")
    @Size(max = 32, message = "手机号最多 32 个字符")
    private String phone;

    /** 新头像的文件 id；null = 不改头像（见类注释）。指向 {@code file_object} 的一行，存 id 不存 URL。 */
    private Long avatarFileId;
}
