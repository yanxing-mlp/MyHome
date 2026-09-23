package com.familyhome.vault.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑密码本条目（全量覆盖 name / account，password 见下）。
 *
 * <p><b>日志约束</b>：{@code @Data} 生成的 {@code toString()} 含 password 字段值，
 * 本类实例禁止进任何日志或异常消息（见 {@code com.familyhome.vault.biz} 包注释第 2 条）。
 */
@Data
public class VaultAccountUpdateRequest {

    @NotBlank(message = "请输入平台名称")
    @Size(max = 64, message = "平台名称最多 64 个字符")
    private String name;

    @NotBlank(message = "请输入账号")
    @Size(max = 128, message = "账号最多 128 个字符")
    private String account;

    /**
     * 新口令的<b>传输层密文</b>（加密口径同 {@code VaultAccountCreateRequest#password}）。
     * <b>为空表示"不改密码"</b>，不是"把密码清空"。
     *
     * <p>为什么不做成必填：列表/详情接口永远拿不到明文（方案 §5.3），所以前端编辑弹窗里
     * 无法回填一个可用的 password 值，保存时也就没法"原样提交"。如果这里要求必填，
     * 用户改个平台名就得重新输入一遍口令。
     *
     * <p>长度规则同样不放在这里（密文长度与口令长度无关），见 {@code VaultAccountService#update}。
     */
    private String password;
}
