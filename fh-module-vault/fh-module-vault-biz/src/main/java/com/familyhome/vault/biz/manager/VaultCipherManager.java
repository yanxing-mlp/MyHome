package com.familyhome.vault.biz.manager;

import com.familyhome.vault.biz.config.VaultProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 口令的可逆加解密：AES-256-GCM。
 *
 * <p><b>为什么是 GCM 而不是 CBC</b>：CBC + PKCS5 没有完整性校验，密文被篡改或列错位时
 * 解出来是垃圾字节而不是异常（padding 有 1/256 概率碰巧合法），口令场景宁可当场报错。
 * GCM 自带 16 字节认证标签，篡改必失败。
 *
 * <p><b>密文格式</b>：{@code base64( iv[12] || ciphertext || tag[16] )}。
 * IV 每次随机（{@link SecureRandom}）拼在前面，解密时切出来。
 * IV 重复是 GCM 的致命错误（会泄露明文异或、破坏认证密钥），所以<b>绝不</b>用固定 IV，
 * 也不做"相同口令得到相同密文"的确定性加密——本域没有按密文检索的需求。
 *
 * <p><b>残留风险（方案 §10 风险 11）</b>：加密是为了"拖库不脱库"，密钥在配置文件里，
 * 所以「数据库 + 配置文件」同时泄露就等于全裸。真正的防护是密钥只走环境变量注入、
 * 以及 B 端接口只在内网可达（§8.3）。一期不做 KMS/HSM，也不做密钥轮换。
 */
@Slf4j
@Component
public class VaultCipherManager {

    /** 12 字节 IV 是 GCM 的标准推荐长度（96 bit，配合随机 IV 不会撞）。 */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度，单位 bit。 */
    private static final int TAG_LENGTH_BIT = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public VaultCipherManager(VaultProperties properties) {
        this.key = parseKey(properties.getPasswordKey());
        log.info("vault 口令加密已就绪: algorithm=AES-256-GCM");
    }

    /**
     * 启动时就校验密钥，配错要立刻炸。
     *
     * <p>不能等到第一次解密才发现——那时已经有明文口令以错误密钥加密入库了。
     */
    private static SecretKeySpec parseKey(String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            throw new IllegalStateException(
                    "fh.vault.password-key 未配置：密码本无法加解密口令。"
                            + "生成方式：openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("fh.vault.password-key 不是合法的 Base64", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("fh.vault.password-key 必须是 32 字节（当前 "
                    + decoded.length + " 字节）。生成方式：openssl rand -base64 32");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /**
     * @param plaintext 明文口令，可为 null（调用方决定"不修改口令"时根本不加密）
     * @return 密文；入参为 null 时返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            // 不带上任何明文信息
            throw new IllegalStateException("口令加密失败", e);
        }
    }

    /**
     * @param cipherText {@link #encrypt(String)} 的产物
     * @return 明文口令，调用方拿到后应尽快输出、不要长期持有、不要打日志
     */
    public String decrypt(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            throw new IllegalStateException("口令密文为空，无法解密");
        }
        byte[] packed = Base64.getDecoder().decode(cipherText);
        if (packed.length <= IV_LENGTH) {
            throw new IllegalStateException("口令密文长度不合法");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BIT, packed, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException 走到这里：密钥变了，或密文被改过
            throw new IllegalStateException("口令解密失败：主密钥与数据不匹配，请核对 fh.vault.password-key", e);
        }
    }
}
