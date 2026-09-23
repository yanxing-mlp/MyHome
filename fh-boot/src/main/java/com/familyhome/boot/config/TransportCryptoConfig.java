package com.familyhome.boot.config;

import com.familyhome.common.crypto.TransportCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 {@link TransportCipher}。
 *
 * <p>为什么在这里 new 而不是给 {@code TransportCipher} 打 {@code @Component}：
 * fh-common 刻意不依赖 spring-context（只留注解与 JSON，见它的 pom），
 * 一旦为这个类破例，common 就再也摘不掉 Spring。配置项的读取形状与
 * {@code WebMvcConfig} 里的 {@code fh.storage.*} 一致，用 {@code @Value}。
 *
 * <p>盐从 {@code fh.transport-crypto.salt} 来：dev 有仓库内公开的默认值，prod 只从环境变量
 * {@code FH_TRANSPORT_CRYPTO_SALT} 注入。<b>漏配时构造器就地抛异常 → 启动失败</b>，
 * 与 {@code VaultCipherManager} 校验主密钥同一手法：宁可起不来，也不要"服务活着但没人登得进来"。
 */
@Slf4j
@Configuration
public class TransportCryptoConfig {

    @Bean
    public TransportCipher transportCipher(@Value("${fh.transport-crypto.salt:}") String salt) {
        TransportCipher cipher = new TransportCipher(salt);
        // 只报"就绪"，绝不把那串盐落进日志文件
        log.info("口令传输层加密已就绪: algorithm=AES-256-GCM, kdf=PBKDF2-SHA256/{}轮",
                TransportCipher.KDF_ITERATIONS);
        return cipher;
    }
}
