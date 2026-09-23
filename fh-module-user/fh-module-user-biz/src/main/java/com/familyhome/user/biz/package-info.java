/**
 * 账号域实现（全家成员 + 登录 + 账号管理）。
 *
 * <p>表前缀 {@code app_}（只有一张 {@code app_user}），Flyway 目录 {@code db/migration/user}
 * （版本号段 V5xx）。
 *
 * <p><b>本域只做三件事，边界刻意很小：</b>
 * <ol>
 *   <li><b>存"家里有哪几个人"</b>：昵称 + 手机号 + 口令哈希 + 头像 + 角色。口令是
 *       PBKDF2 <b>哈希</b>（{@code UserPasswordManager}），不是密码本那套可逆密文——
 *       登录只要"验一下对不对"，不需要还原明文，所以<b>存储段</b>这一把密钥也没有（V502 有理由）。
 *       v11 起本域确实注入了一个 {@code TransportCipher}，但那是<b>传输段</b>共用的（密码本也用同一个），
 *       解完立刻进哈希比对，明文不落任何字段。</li>
 *   <li><b>认"现在是谁"</b>：{@code POST /login} 核对下拉选中的账号 + 手填的口令，通过后服务端
 *       用 {@code SessionToken}（HMAC-SHA256）签一枚令牌随返回体给前端，前端存本机、之后每个请求带
 *       {@code Authorization: Bearer <token>} 头，由 fh-boot 的拦截器<b>验签验有效期</b>后写进
 *       {@code CurrentUserHolder}。<b>身份不再由客户端自报</b>：旧版本前端送一个可手搓的
 *       {@code X-User-Id}，等于谁都能冒充大宝，已废弃。令牌里只有 userId + 过期时间，<b>不含角色</b>，
 *       角色每次请求现查库（{@code AppUserService.resolve}），改角色/删号立刻生效。
 *       服务端<b>仍不存 session</b>（令牌自证，无需服务端状态），口令只在登录这一次出现在请求体里
 *       （v11 起是传输层密文，不是明文）；换设备/清缓存的代价是"再输一次密码"，令牌到期（7 天）也一样。</li>
 *   <li><b>给别的域一个可空的 {@code creator_id}</b>：写"添加人"的七条插入路径都调
 *       {@code CurrentUserHolder.requireUserId()}，因此<b>没有任何一个域依赖本模块</b>
 *       （连 {@code fh-module-user-api} 都不依赖，见 api 包注释）。</li>
 * </ol>
 *
 * <p><b>两态删除</b>：{@code deleted TINYINT} + {@code @TableLogic}，与 {@code file_object} /
 * {@code vault_account} 同构；账号没有"下架"语义，不要为了跟 {@code album_group} 统一去改三态。
 * 也正因为软删，{@code name} / {@code phone} 都<b>没有唯一索引</b>（"删掉再用同名重建"会撞库），
 * 重名靠 {@link com.familyhome.user.biz.service.AppUserService} 里的查询判重，见 V500 迁移注释。
 *
 * <p><b>权限只有两条规则</b>：账号管理的列表/新建/改角色/删除要 {@code role = ADMIN}（默认超管是
 * {@code V500} 种子里的大宝），{@code CurrentUserHolder.requireAdmin()} 是唯一判据；个人中心那三条
 * 只要求"有身份"，因为改的目标就是自己。<b>两条之间没有第三条"管理员替别人改资料"</b>：
 * 管理员能替别人写的<b>只有 {@code role} 这一列</b>（一条一次只动一列的 {@code PUT /{id}/role}，
 * 并且挡住"改自己"与"降掉最后一个管理员"），昵称、手机号、口令、头像四项都只能由本人在个人中心动，
 * 他既不看、不重置口令，也不改任何人的资料（建号时那个统一初始口令他也不知道）；头像建号时由管理员
 * 先定一张，之后本人可在个人中心自己换。所以"改资料"与"改口令"这两件事在整个系统里各只有
 * 一个入口，而"定谁是超管"只有一个入口。前端隐藏菜单只是体验，服务端这道才是边界。
 * {@code /login} 与 {@code /options} 两条则完全不要身份，因为登录页拉下拉列表时还没有人。
 */
package com.familyhome.user.biz;
