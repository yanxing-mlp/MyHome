package com.familyhome.vault.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增账号本条目。
 *
 * <p><b>日志约束</b>：{@code @Data} 生成的 {@code toString()} 含明文 password，
 * 本类实例禁止进任何日志或异常消息（见 {@code com.familyhome.vault.biz} 包注释第 2 条）。
 */
@Data
public class VaultAccountCreateRequest {

    /** 平台名，如 微信 / steam / QQ */
    @NotBlank(message = "请输入平台名称")
    @Size(max = 64, message = "平台名称最多 64 个字符")
    private String name;

    @NotBlank(message = "请输入账号")
    @Size(max = 128, message = "账号最多 128 个字符")
    private String account;

    @NotBlank(message = "请输入密码")
    @Size(max = 256, message = "密码最长 256 个字符")
    private String password;
}
