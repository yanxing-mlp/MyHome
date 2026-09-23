package com.familyhome.common.auth;

import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 登录会话令牌的<b>签发与校验</b>：登录成功时服务端签一枚，之后每个请求带回来，
 * 拦截器验签 + 验有效期，通过了才把令牌里的 userId 换成"当前登录人"。
 *
 * <p><b>它替代的是原来那个可手搓的 {@code X-User-Id} 请求头</b>。旧机制里"你是谁"完全由客户端
 * 自报，服务端只查"这个 id 的账号还在不在"，于是任何人 {@code curl -H 'X-User-Id: 1'} 就成了大宝
 * （ADMIN）——登录时那次口令核对形同虚设，因为身份不过是一个能重放的数字。这一层把身份改成
 * <b>服务端用只有它自己知道的密钥签出来的、带有效期的、改一个字符就验不过的</b>令牌，
 * 口令核对的结果第一次变得"带得走、也伪造不了"。
 *
 * <p><b>令牌里只放 userId 和过期时间，不放 role、不放 name</b>：拦截器拿到 userId 之后照旧调
 * {@code AppUserService.resolve()} 从库里取现值。于是"改了某人的角色""删了某个号"下一次请求立即生效，
 * 不必等令牌过期；也避免了"客户端手里那枚令牌自称是 ADMIN"这种把权限写进可重放字符串的反模式。
 *
 * <p><b>格式</b>：{@code v1.<userId>.<过期秒(epoch)>.<base64url(HMAC-SHA256(前三段))>}。
 * 三段明文 + 一段签名，用 {@code .} 连接；校验时对"最后一个点之前的全部"重算 HMAC 再<b>定长时间比较</b>
 * （{@link MessageDigest#isEqual}），杜绝按字节短路比较留下的计时侧信道。base64 用 <b>URL 安全、无填充</b>
 * 变体，令牌可以直接放进 {@code Authorization: Bearer} 头而不需要再转义。
 *
 * <p><b>为什么自己写而不上 JWT 库</b>：这里只需要"对称签名 + 一个 exp"，JDK 自带的
 * {@code HmacSHA256} 就够了，引一个 JWT 依赖是多几十 KB 的传递依赖和一个新的版本面。
 * 手法与同仓的 {@code TransportCipher}（口令传输段加解密）一致——都是 fh-common 里手写的 JDK crypto，
 * 都不打 Spring 注解（bean 由 fh-boot 装配）。
 *
 * <p><b>放在 fh-common</b>：签发方是 user 域（登录），校验方是 fh-boot 的拦截器，
 * 两者唯一的公共依赖就是 fh-common（与 {@code CurrentUserHolder}、{@code TransportCipher} 同一个理由）。
 */
public class SessionToken {

    /**
     * 令牌有效期：7 天。够长以维持"登录一次、这台设备上就一直是他"的家用体验
     * （令牌与展示用的当前账号一起存本机，见前端 {@code shared/auth}），
     * 又足够短，让一枚泄漏的令牌不会永久有效。不做续期接口（一期没有这个需求）：
     * 到期后前端吃到 401 会清本机登录态、回登录页重登一次即可。
     */
    public static final long TTL_SECONDS = 7L * 24 * 60 * 60;

    /** 密钥短于这个长度时，暴力枚举密钥 ≈ 直接伪造令牌，这一层就只剩心理作用。 */
    private static final int MIN_SECRET_CHARS = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final char SEP = '.';

    private final SecretKeySpec key;

    /**
     * @param secret 只有服务端知道的签名密钥。<b>启动时就校验</b>，配漏了当场炸——
     *               与 {@code TransportCipher} 校验盐、{@code VaultCipherManager} 校验主密钥同一手法：
     *               宁可起不来，也不要"服务活着但谁都登不进来 / 谁都拦不住"。
     */
    public SessionToken(String secret) {
        this.key = buildKey(secret);
    }

    private static SecretKeySpec buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "fh.auth.token-secret 未配置：没有它服务端既签不出登录令牌、也验不了任何请求，"
                            + "等于全站不可用。生成方式：openssl rand -base64 48");
        }
        String trimmed = secret.trim();
        if (trimmed.length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException("fh.auth.token-secret 至少 " + MIN_SECRET_CHARS
                    + " 个字符（当前 " + trimmed.length() + "）");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) > 0x7F) {
                throw new IllegalStateException("fh.auth.token-secret 只能用 ASCII 字符");
            }
        }
        return new SecretKeySpec(trimmed.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * 给某个 userId 签一枚现在起 {@link #TTL_SECONDS} 秒内有效的令牌。只在登录成功那一刻调用。
     *
     * @return 可直接放进 {@code Authorization: Bearer} 的紧凑字符串。<b>不落日志</b>：
     *         它等价于一张"有效期内的登录凭证"，进日志就是把凭证摊给所有能看日志文件的人。
     */
    public String issue(long userId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = VERSION + SEP + userId + SEP + exp;
        return payload + SEP + sign(payload);
    }

    /**
     * 校验令牌并取出里面的 userId。
     *
     * <p><b>任何不通过都只抛一句 {@code USER_NOT_LOGIN}（401），不区分"签名不对 / 过期 / 格式烂"</b>：
     * 对调用方（前端）来说这三者都是同一件事——回登录页重登；分细了只会给攻击者一个"我离伪造还差哪一步"
     * 的探针。真正的原因按需由调用方在服务端日志里补一条不含令牌原文的 warn。
     *
     * @throws BizException 令牌为空、格式不对、签名对不上、或已过期
     */
    public long verify(String token) {
        if (token == null || token.isBlank()) {
            throw notLogin();
        }
        String trimmed = token.trim();
        int lastSep = trimmed.lastIndexOf(SEP);
        if (lastSep <= 0 || lastSep == trimmed.length() - 1) {
            throw notLogin();
        }
        String payload = trimmed.substring(0, lastSep);
        String givenSig = trimmed.substring(lastSep + 1);

        // 定长时间比较：先算出期望签名，再整体比对，不因"第几个字符开始不一样"提前返回
        byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = givenSig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw notLogin();
        }

        // 签名对了才值得解析明文段；解析失败同样按"没登录"处理
        String[] parts = payload.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw notLogin();
        }
        long userId;
        long exp;
        try {
            userId = Long.parseLong(parts[1]);
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw notLogin();
        }
        if (exp < Instant.now().getEpochSecond()) {
            throw notLogin();
        }
        return userId;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            // 密钥已在校验过的 SecretKeySpec 里，正常情况下到不了这里
            throw new IllegalStateException("会话令牌签名失败", e);
        }
    }

    private static BizException notLogin() {
        return BizException.of(ErrorCode.USER_NOT_LOGIN, "登录状态已失效，请重新登录");
    }
}
