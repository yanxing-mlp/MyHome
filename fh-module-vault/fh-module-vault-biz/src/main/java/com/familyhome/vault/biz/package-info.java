/**
 * 账号本域实现（v5 需求）。
 *
 * <p>表前缀 {@code vault_}，Flyway 目录 {@code db/migration/vault}（版本号段 V4xx）。
 *
 * <p><b>本域是全库唯一存放明文凭据的地方，所以它的三条口径和别的域不一样，改动前先读：</b>
 * <ol>
 *   <li><b>密文不出库</b>：{@code VaultAccountAdminVO} 里<b>没有</b>密码字段（不是返回掩码，
 *       是字段都不给）。明文只能从 {@code POST /api/b/vault/accounts/{id}/password/reveal}
 *       单独取，因此访问日志里不会有凭据、列表接口也永不泄露。</li>
 *   <li><b>密码不进日志</b>：{@code model/request} 下两个请求类都带 {@code password} 字段，
 *       {@code @Data} 会给它们生成 {@code setPassword(String)} 和把口令拼进去的 {@code toString()}。
 *       <b>这两个类的实例禁止出现在任何日志/异常消息里</b>，禁止 {@code log.info("...{}", request)}；
 *       要记日志就记 {@code id} 和 {@code name}。新增带凭据的 DTO 时重新检查一遍。</li>
 *   <li><b>两态删除</b>：{@code deleted TINYINT} + {@code @TableLogic}，与 {@code album_group} 同构，
 *       <b>不是</b> {@code recipe} / {@code album_image} 的三态 {@code status}（那个要手写
 *       {@code status <> 'DELETED'}，见方案 §10 风险 5）。账号没有"下架"语义，不要为了统一去改三态。</li>
 * </ol>
 *
 * <p>加密是<b>可逆</b>的 AES-256-GCM（用户要"存储"口令，必须能还原），密钥在配置文件里。
 * 这意味着拿到「库 + 配置文件」就能批量解密——真正的防护是密钥不落仓库（生产走环境变量）
 * 和访问控制（方案 §8.3 的 nginx 内网白名单）。方案 §10 风险 11 记了这个残留。
 */
package com.familyhome.vault.biz;
