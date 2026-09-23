package com.familyhome.common.crypto;

import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令的<b>传输段</b>加解密：前端提交前加密，服务端在业务逻辑入口解密；
 * 反向只有密码本 reveal 一处（服务端加密返回，前端解密后展示）。
 *
 * <p><b>它防的是链路，不是端</b>。密钥（那一串盐）在浏览器 bundle 里就有一份，
 * 所以任何能读前端代码的人都拿得到明文——这一层挡的是"http 明文请求在局域网里被顺手抓包"
 * 和"口令落进 nginx access log / 请求日志"，不是挡住一个铁了心的攻击者。
 * <b>它不是 TLS 的替代</b>：真正的链路加密要在 §8.3 那份 nginx 上配 https。
 *
 * <p><b>为什么不直接换成"前端传哈希"</b>：那需要把 {@code app_user.password_hash} 改成
 * "存前端算出来的摘要"（digest-as-password），等于把那串摘要变成新的口令本身，
 * 而且改不动密码本（那边必须可逆）。这里的取舍是让<b>存储层一条口径都不变</b>：
 * 解出明文之后，走的仍然是原来那套 {@code UserPasswordManager}（PBKDF2 单向哈希）
 * 与 {@code VaultCipherManager}（服务端 AES-256-GCM 可逆加密）。
 * 于是登录口令<b>依然不可逆</b>——"前后端都能解密"只发生在传输段这一次解密的进程内。
 *
 * <p><b>格式必须与前端逐字节对齐</b>（{@code packages/shared/src/crypto/transport.ts}）：
 * PBKDF2-HMAC-SHA256（10 万轮、以盐串自身为盐、输出 256 bit）派生 AES-256 密钥，
 * AES-GCM（12 字节随机 IV、128 bit 认证标签），线上编码是 {@code base64(IV || 密文+标签)}。
 * 三处常量（轮数、IV 长度、标签长度）任何一侧改动都必须同步另一侧，
 * 否则对面只会得到一句"解不开"，所以它们都写成公开常量而不是各自散在实现里。
 *
 * <p><b>放在 fh-common</b>：user 域与 vault 域都要用它，而两个域之间唯一的公共依赖就是
 * fh-common（与 {@code CurrentUserHolder} 同一个理由）。放任何一个域里都会凭空多出一条跨域边。
 *
 * <p><b>不加 Spring 注解</b>：fh-common 刻意不依赖 spring-context（见它的 pom），
 * 这个 bean 由 fh-boot 的 {@code TransportCryptoConfig} 装配。
 */
public class TransportCipher {

    /** 与前端 {@code ITERATIONS} 必须一致。登录链路每次都要跑这一趟，10 万轮在浏览器里是个位数毫秒。 */
    public static final int KDF_ITERATIONS = 100_000;
    /** 与前端必须一致：派生出 256 bit 的 AES-256 密钥。 */
    public static final int KEY_LENGTH_BITS = 256;
    /** 与前端必须一致：GCM 的标准 IV 长度。 */
    public static final int IV_LENGTH_BYTES = 12;
    /** 与前端必须一致：GCM 认证标签长度（Web Crypto 只支持 128 bit）。 */
    public static final int TAG_LENGTH_BITS = 128;

    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** 盐短于这个长度时，暴力枚举盐 ≈ 暴力枚举口令，这层加密就只剩心理作用。 */
    private static final int MIN_SALT_CHARS = 16;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param salt 前后端共用的那一串盐。<b>启动时就校验</b>，配漏了要当场炸——
     *             不能等到第一次有人登录才发现"前端加密了、后端解不开"，那是一条全站登录不可用的故障。
     */
    public TransportCipher(String salt) {
        this.key = deriveKey(salt);
    }

    /**
     * 盐串 → AES-256 密钥。
     *
     * <p>盐串同时充当 PBKDF2 的口令与盐两个入参（它本来就是一份两端共享的秘密，
     * 不是"每次随机的Nonce"）。这要求它是 ASCII：SunJCE 的 PBKDF2 与 Web Crypto 都按 UTF-8
     * 编码口令字符，纯 ASCII 时两边逐字节一致；含非 ASCII 字符时两边编码路径容易在
     * 代理对、normalization 上出差异，所以直接拒绝。
     */
    private static SecretKey deriveKey(String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException(
                    "fh.transport-crypto.salt 未配置：前端已按加盐密文提交口令，服务端解不开等于全站登录不可用。"
                            + "生成方式：openssl rand -hex 16");
        }
        String trimmed = salt.trim();
        if (trimmed.length() < MIN_SALT_CHARS) {
            throw new IllegalStateException("fh.transport-crypto.salt 至少 " + MIN_SALT_CHARS
                    + " 个字符（当前 " + trimmed.length() + "）");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) > 0x7F) {
                throw new IllegalStateException("fh.transport-crypto.salt 只能用 ASCII 字符，"
                        + "否则前后端 PBKDF2 的输入编码可能不一致");
            }
        }
        PBEKeySpec spec = new PBEKeySpec(trimmed.toCharArray(), trimmed.getBytes(StandardCharsets.UTF_8),
                KDF_ITERATIONS, KEY_LENGTH_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("传输层加密密钥派生失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 解开前端送上来的口令。
     *
     * <p><b>没有"传明文就照单收下"这条路</b>：留一个降级分支等于给"绕过这层加密"开了个正式入口，
     * 而调用方（前端）已经不会再发明文了。直连接口调试请走 {@code deploy/transport-crypto.mjs}
     * 生成密文。失败一律 400：请求体本身不合法，不是"口令不对"（那一句是
     * {@code USER_PASSWORD_MISMATCH}，两件事不能说混）。
     *
     * @return 明文口令，调用方用完即弃，<b>不写日志、不塞进 DTO、不进返回值</b>
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入密码");
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(cipherText.trim());
        } catch (IllegalArgumentException e) {
            throw badCipher();
        }
        if (packed.length <= IV_LENGTH_BYTES) {
            throw badCipher();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, packed, 0, IV_LENGTH_BYTES));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH_BYTES, packed.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException 也走这里：盐不一致，或者这串东西根本不是前端加密的
            throw badCipher();
        }
    }

    /**
     * 给要下发给前端的口令加密。目前<b>只有</b>密码本 reveal 一个调用方。
     *
     * <p>与 {@link #decrypt} 对称地不做"明文直接返回"的降级：那样一来
     * "服务端会不会把明文吐到网络上"重新变成一个看代码回答不了的问题。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("encrypt 不接受 null");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("口令加密失败", e);
        }
    }

    /**
     * 只说"解不开"，不把 {@code GeneralSecurityException} 的原文带出去：
     * 那里面可能区分出"Base64 不对"和"GCM 标签校验失败"，对排查有用、对攻击者也有用。
     * 真正的分支在服务端日志里由调用方按需补一条 warn。
     */
    private static BizException badCipher() {
        return BizException.of(ErrorCode.BAD_REQUEST,
                "口令无法解密，请刷新页面后重试（前后端加密参数不一致）");
    }
}
