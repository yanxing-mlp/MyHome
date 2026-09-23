package com.familyhome.vault.biz.model.vo.admin;

import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 密码本条目的 B 端视图。
 *
 * <p><b>这里没有 password / passwordEnc 字段，也不是"返回掩码"</b>——字段压根不存在，
 * 所以列表和详情接口在任何情况下都不可能把凭据带出去。要口令只能显式调
 * {@code POST /api/b/vault/accounts/{id}/password/reveal}（方案 §5.3），而那条响应里也是传输层密文。
 *
 * <p>时间直接给 ISO-8601 字符串（与 {@code /api/b/health} 一致），展示格式由前端
 * {@code formatDateTime} 统一处理，不在后端拼字符串——C 端二期可能要另一种格式。
 */
@Getter
@Setter
public class VaultAccountAdminVO {

    private Long id;

    /** 平台名，如 微信 / steam / QQ */
    private String name;

    /** 账号，明文可展示 */
    private String account;

    /** 公共 / 私人分区。 */
    private DataScope scope;

    /** 公共分区为 0，私人分区为所属账号 ID。 */
    private Long ownerId;

    /** 添加人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
