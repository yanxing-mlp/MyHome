package com.familyhome.vault.biz.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 密码本域配置（{@code fh.vault.*}）。
 *
 * <p>放在 vault-biz 而不是 fh-boot：配置项属于业务域自己，fh-boot 只做启动聚合（方案 §3.1）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fh.vault")
public class VaultProperties {

    /**
     * AES-256 主密钥，<b>Base64 编码的 32 字节</b>。
     *
     * <p>读取顺序：dev profile 里写死一个（仓库内可见，只用于本机测试数据）；
     * 生产通过环境变量 {@code FH_VAULT_PASSWORD_KEY} 注入，不落仓库。
     *
     * <p><b>换密钥 = 老数据全部解不出来</b>，没有密钥轮换设计（家庭场景一条口令用到底）。
     * 由 {@code VaultCipherManager} 在启动时校验长度，配错就地失败，不要拖到第一次解密。
     */
    private String passwordKey;
}
