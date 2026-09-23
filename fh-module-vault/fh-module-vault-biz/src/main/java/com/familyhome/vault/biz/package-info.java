/**
 * 密码本域实现，公共密码与私人密码严格分区。
 *
 * <p>表前缀 {@code vault_}，Flyway 目录 {@code db/migration/vault}（版本号段 V4xx）。
 * 所有入口要求登录，并用查询参数 scope 选择 PUBLIC（默认）或 PRIVATE。
 * PUBLIC 的 ownerId 固定为 0；PRIVATE 的 ownerId 为当前登录账号，ADMIN 也不能越权。
 * 创建时由服务端赋值，后续不得修改 scope / ownerId；creatorId 仅记录真实添加人。
 * 列表、判重、编辑、软删、口令查看都限定当前分区，跨分区 ID 与不存在统一 404。
 *
 * <p><b>本域存放「可还原凭据」，改动前须遵守以下约束：</b>
 * <ol>
 *   <li><b>密文不出库</b>：{@code VaultAccountAdminVO} 里<b>没有</b>密码字段（不是返回掩码，
 *       是字段都不给）。口令只能从 {@code POST /api/b/vault/accounts/{id}/password/reveal}
 *       单独取，口令不放入 URL，列表接口也不返回凭据。
 *       <b>v11 起那一条响应的 {@code password} 也不是明文</b>，是传输层密文（见下方加密说明）。</li>
 *   <li><b>密码不进日志</b>：{@code model/request} 下两个请求类都带 {@code password} 字段，
 *       {@code @Data} 会给它们生成 {@code setPassword(String)} 和把口令拼进去的 {@code toString()}。
 *       <b>这两个类的实例禁止出现在任何日志/异常消息里</b>，禁止 {@code log.info("...{}", request)}；
 *       要记日志就记 {@code id} 和 {@code name}。新增带凭据的 DTO 时重新检查一遍。
 *       入参那一格 v11 后是密文，但"密文进日志"依然等于"凭据进日志"（拿到密文 + 前端那串盐就能解）。</li>
 *   <li><b>两态删除</b>：{@code deleted TINYINT} + {@code @TableLogic}，与 {@code file_object} 同构，
 *       <b>不是</b> {@code recipe} / {@code album_image} / {@code album_group} 的三态 {@code status}
 *       （那三张表要手写 {@code status <> 'DELETED'}，见方案 §10 风险 5；
 *       {@code album_group} 本来也是两态，为"分组下架"在 V208 换成了三态）。账号没有"下架"语义，不要为了统一去改三态。</li>
 * </ol>
 *
 * <p>加密是<b>可逆</b>的 AES-256-GCM（用户要"存储"口令，必须能还原），生产密钥走环境变量，
 * 不落仓库。拿到数据库和密钥仍可解密，因此登录校验与分区权限不可被加密替代，
 * nginx 内网白名单可作为额外保护，不能替代服务端访问控制。
 * 这一把 key 只管<b>存储段</b>；<b>传输段</b>另有其钥（{@code com.familyhome.common.crypto.TransportCipher}
 * 用 {@code fh.transport-crypto.salt} 派生，前端各算各的同一串盐）。两把钥匙互不通用，
 * 别把 {@code fh.vault.password-key} 拿去解前端的报文，也别反过来。
 */
package com.familyhome.vault.biz;
