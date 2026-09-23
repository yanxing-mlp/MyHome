package com.familyhome.vault.biz.model.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * reveal 接口的响应：全系统唯一会把口令（解得开的形式）带出后端的地方。
 *
 * <p>只有两个字段、不可复用为列表项、不带库里那列密文，避免它被顺手塞进别的响应里。
 *
 * <p><b>v11 起 {@code password} 不是明文</b>：它是用传输层的盐加密后的 Base64 串，只有前端
 * {@code packages/shared/src/crypto/transport.ts} 能解开，明文只存在于浏览器内存里，
 * 不出 Network 面板的响应体。存储列 {@code password_enc} 仍是服务端自己那把
 * {@code fh.vault.password-key} 的 AES-256-GCM 密文——两把钥匙、两段链路，各管各的。
 */
@Getter
@AllArgsConstructor
public class VaultPasswordVO {

    private Long id;

    /** 口令的传输层密文（前端解密后展示） */
    private String password;
}
