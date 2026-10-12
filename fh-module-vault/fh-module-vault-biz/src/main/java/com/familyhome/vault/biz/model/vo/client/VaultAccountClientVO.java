package com.familyhome.vault.biz.model.vo.client;

import lombok.Getter;
import lombok.Setter;

/**
 * 密码本条目的 C 端视图（只读列表行）。
 *
 * <p>是 B 端 {@link com.familyhome.vault.biz.model.vo.admin.VaultAccountAdminVO} 的裁剪版：C 端只"看"，
 * 不展示添加人，也不下发分区 / 属主 / 创建·更新时间这些内页用不到的内部字段——与视频 C 端只回
 * {@code VideoClientVO}、相册 C 端只回 {@code AlbumImageBriefVO} 那几列同一口径。裁剪在
 * {@code VaultCController} 里做，服务层仍返回 {@code VaultAccountDO}。
 *
 * <p><b>这里没有 password / passwordEnc 字段</b>——口令只能显式调
 * {@code POST /api/c/vault/accounts/{id}/password/reveal} 取回，且那一格也是传输层密文（见 {@code VaultPasswordVO}）。
 * 列表在任何情况下都不可能把凭据带出去。
 */
@Getter
@Setter
public class VaultAccountClientVO {

    private Long id;

    /** 平台名，如 微信 / steam / QQ */
    private String name;

    /** 账号，明文可展示（口令不在这里，走 reveal） */
    private String account;
}
