package com.familyhome.vault.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增密码本条目。
 *
 * <p><b>日志约束</b>：{@code @Data} 生成的 {@code toString()} 含 password 字段值，
 * 本类实例禁止进任何日志或异常消息（见 {@code com.familyhome.vault.biz} 包注释第 2 条）。
 * v11 之后那个字段是传输层密文而不是明文，但约束照旧——密文能解出明文，写进日志等于写进明文。
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

    /**
     * 口令的<b>传输层密文</b>（前端用盐派生密钥后 AES-256-GCM 加密，见
     * {@code com.familyhome.common.crypto.TransportCipher} 与 {@code packages/shared/src/crypto/transport.ts}）。
     *
     * <p><b>这里刻意没有长度校验</b>：密文长度 = 明文长度 + 12 字节 IV + 16 字节 GCM 标签，再整体 Base64，
     * 跟用户口令的实际长度不成正比，加任何 {@code @Size} 都只会把正常请求挡掉。
     * "明文最长 256 字符"那条规则挪到了 {@code VaultAccountService} 解密的下一步。
     */
    @NotBlank(message = "请输入密码")
    private String password;
}
