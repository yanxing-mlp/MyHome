package com.familyhome.user.biz.manager;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 登录口令的哈希与校验：PBKDF2-HMAC-SHA256，每行一个随机盐。
 *
 * <p><b>为什么不用密码本那套 AES-256-GCM</b>：{@code VaultCipherManager} 的存在理由是"存进去
 * 还要能拿回明文"（reveal 口令给家人看），那是可逆加密唯一说得通的场景。登录只需要比对，
 * 用可逆加密等于把"密钥 + 数据库同时泄露"的代价从"看得到口令"变成"看得到全部口令"，
 * 而且这里根本没有任何一处需要还原明文。<b>存储段这一把不引密钥、不引配置项</b>：参数全在常量里，
 * 存量串里又带着迭代次数，所以换这台机器、改这个类都不影响已有口令能验得过。
 * （v11 的 {@code TransportCipher} 确实用了一串配置里的盐，但那是<b>传输段</b>、密码本与本域共用一个 bean，
 * 与本类无关：那一层解出来的明文到了这里，照旧只进 {@link #hash} 与 {@link #matches}。）
 *
 * <p><b>为什么用 JDK 自带的 PBKDF2 而不是 BCrypt</b>：仓库没有 spring-security 依赖，
 * 离线构建（{@code mvn -o}）下多一个坐标就多一次"这个 jar 到底在不在本地仓库"的赌。
 * PBKDF2 是 JDK 标准算法，迭代次数可调到 20 万轮（本机实测约 50–100ms），
 * 而家庭场景的真实边界仍然是 §8.3 那道 nginx 内网白名单，不是这里的算力。
 *
 * <p><b>串的格式</b>：{@code pbkdf2_sha256$<迭代次数>$base64(盐)$base64(哈希)}。
 * 迭代次数写在串里，是为了<b>调高常量不会把存量口令一次性废掉</b>——否则改了参数之后
 * 所有人登录都报"密码不正确"，而这句话在这个系统里没有任何自助恢复路径。
 *
 * <p><b>日志</b>：明文口令与哈希都不打。校验失败由调用方（{@code AppUserService#login}）记一条
 * 只含 id + name 的 warn，与本域"手机号不进日志"同一条口径。
 */
@Slf4j
@Component
public class UserPasswordManager {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 串的第一段，用来一眼认出自己写的格式；也是 {@link #matches} 的合法性检查之一。 */
    private static final String PREFIX = "pbkdf2_sha256";
    /** 盐 16 字节：只要唯一就够（随机盐的作用是掐死彩虹表和"两人同口令同密文"）。 */
    private static final int SALT_LENGTH_BYTES = 16;
    /** 输出 256 bit，与 HMAC-SHA256 的原生长度一致，截断不会白丢算力。 */
    private static final int HASH_LENGTH_BITS = 256;
    /** 20 万轮 ≈ 本机 50–100ms。一天登一次，这个税交得起。 */
    private static final int ITERATIONS = 200_000;
    private static final int SEGMENTS = 4;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 把明文口令算成入库的那一串。
     *
     * <p>调用方保证 {@code plain} 非空（请求体上有 {@code @NotBlank} + 最短长度校验），
     * 这里刻意不做"空就返回 null"的宽容处理：本域没有"口令留空即不改"的语义，
     * 那一套属于密码本（改记录时可以不碰口令），串味了会让登录侧多出一条说不清的死路。
     */
    public String hash(String plain) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] digest = derive(plain, salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(digest);
    }

    /**
     * 校验明文与存量哈希是否匹配。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：后者一遇到不同的字节就返回，
     * 耗时随"前缀重合了几个字节"变化，理论上能被计时侧信道一位一位试出哈希。这个比较是常数时间的，
     * 而且比把两边都再解一次 Base64 更直白。
     *
     * <p>入参为空白、串为空（有人直插 SQL 给了 {@code ''}）、格式不认识、迭代次数不是数字，
     * 一律返回 {@code false}（= 这个账号登不进来），<b>不抛异常</b>：登录接口对外的说法只有
     * "密码与该账号不匹配"，不该因为库里一列脏数据变成 500。
     */
    public boolean matches(String plain, String stored) {
        if (!StringUtils.hasText(plain) || !StringUtils.hasText(stored)) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != SEGMENTS || !PREFIX.equals(parts[0])) {
            log.warn("口令哈希格式不认识，按不匹配处理");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
                return false;
            }
            return MessageDigest.isEqual(expected, derive(plain, salt, iterations));
        } catch (IllegalArgumentException e) {
            // Base64 解不开 / 数字溢出：与格式不认识同一处理
            log.warn("口令哈希解析失败，按不匹配处理: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * PBKDF2 本体。{@code clearPassword()} 不是仪式感：{@code PBEKeySpec} 里的口令是
     * 一个 char[]，在堆上要等到 GC 才消失，能主动抹就抹。
     *
     * <p>算法拿不到 / 参数不合法都直接抛：那是 JDK 或代码写错了，不是用户输错了口令——
     * 让它冒成 500 比"回一个 false 说人家密码不对"诚实。异常信息里不带任何明文。
     */
    private byte[] derive(String plain, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, HASH_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("口令哈希计算失败", e);
        } finally {
            spec.clearPassword();
        }
    }
}
