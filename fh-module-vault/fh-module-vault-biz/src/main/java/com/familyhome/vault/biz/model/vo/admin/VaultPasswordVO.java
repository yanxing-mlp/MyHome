package com.familyhome.vault.biz.model.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * reveal 接口的响应：<b>唯一</b>会把明文口令带出后端的地方。
 *
 * <p>只有两个字段、不可复用为列表项、不带密文，避免它被顺手塞进别的响应里。
 */
@Getter
@AllArgsConstructor
public class VaultPasswordVO {

    private Long id;

    /** 明文口令 */
    private String password;
}
