package com.familyhome.boot.config;

import com.familyhome.common.auth.SessionToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 {@link SessionToken}（登录会话令牌的签发/校验器）。
 *
 * <p>与 {@code TransportCryptoConfig} 同一手法：fh-common 刻意不依赖 spring-context，
 * 所以那个类不打 {@code @Component}，改由这里用 {@code @Value} 读配置再 {@code new}。
 *
 * <p>密钥从 {@code fh.auth.token-secret} 来：dev 有仓库内公开的默认值，prod 只从环境变量
 * {@code FH_AUTH_TOKEN_SECRET} 注入。<b>漏配时构造器就地抛异常 → 启动失败</b>，
 * 与 vault 主密钥、传输盐同一口径：宁可起不来，也不要"服务活着但谁都拦不住"。
 */
@Slf4j
@Configuration
public class AuthTokenConfig {

    @Bean
    public SessionToken sessionToken(@Value("${fh.auth.token-secret:}") String secret) {
        SessionToken sessionToken = new SessionToken(secret);
        // 只报"就绪"和有效期，绝不把那串密钥落进日志文件
        log.info("登录会话令牌已就绪: algorithm=HMAC-SHA256, ttl={}天", SessionToken.TTL_SECONDS / 86400);
        return sessionToken;
    }
}
