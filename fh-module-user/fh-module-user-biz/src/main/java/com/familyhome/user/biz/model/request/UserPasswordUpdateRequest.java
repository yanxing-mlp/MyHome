package com.familyhome.user.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 个人中心改自己的口令：原密码 + 新密码两项。
 *
 * <p><b>为什么必须带原密码</b>：这一条接口只要求"是登录状态"（{@code requireUserId()}），
 * 不要求管理员。登录态在本机是一格 localStorage，谁拿到那台没锁屏的设备都能发这个请求；
 * 再要一句原密码，就把"顺手拿起家人手机改口令"这条路堵上了。<b>而且它是全系统唯一一条改口令的路</b>：
 * 管理员在账号管理页既看不到也改不了别人的口令（那条编辑接口整个下线了），所以忘了口令只能删号重建。
 *
 * <p>最短 6 位是给"家人自己设的口令"定的（服务端建号时那个统一初始口令 {@code 123456} 刚好满足它，
 * 见 {@code AppUserService#DEFAULT_INITIAL_PASSWORD}）。除此之外<b>不做任何强度校验</b>：不查大小写数字符号、
 * 不对照弱口令表、也不管"新密码和旧密码一样"。
 * 家庭场景里口令防的是家人误操作，不是撞库；多一条规则就多一处"为什么我的密码不让设"的疑问
 * （方案 §0 的"最小实现"口径）。确认新密码这件事归前端，服务端收两次里的一次就够。
 *
 * <p><b>⚠️ 两个字段的长度校验为什么都不在这里</b>（v11）：从 v11 起前端提交的是<b>传输层密文</b>
 * （{@code base64(IV || AES-GCM(明文))}，比明文长，且长度由 IV 与 Base64 补齐决定，与口令本身无关），
 * 所以 {@code @Size(min=6,max=64)} 加在这一格上判的就不再是"用户选的口令多长"。
 * 那条 6–64 的规则搬到了 {@code AppUserService#requireNewPasswordLength}——<b>解密之后</b>再判。
 * 这里只留 {@code @NotBlank}：密文非空 ⟺ 明文非空，这一条判据在密文上依然成立。
 */
@Data
public class UserPasswordUpdateRequest {

    /** 原密码的<b>传输层密文</b>，不是明文（见类注释）。 */
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    /** 新密码的<b>传输层密文</b>；6–64 位在 service 解密后判。 */
    @NotBlank(message = "请输入新密码")
    private String newPassword;
}
