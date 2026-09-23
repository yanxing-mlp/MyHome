package com.familyhome.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 家庭 Home 服务端唯一启动入口。
 *
 * <p>{@code scanBasePackages} 指向 {@code com.familyhome}，把五个业务域 biz 模块里的
 * {@code @Service} / {@code @RestController} 全部装配进来——各域 biz 是被 fh-boot 聚合的普通 jar，
 * 自身不带启动类。
 *
 * <p>一期不依赖 album-api / recipe-api：C 端接口全部推迟到二期，所以 fh-boot 不含任何业务逻辑，
 * 只做启动聚合 + 全局配置（方案 §3.1）。
 */
@SpringBootApplication(scanBasePackages = "com.familyhome")
@MapperScan({
        "com.familyhome.file.biz.dao",
        "com.familyhome.album.biz.dao",
        "com.familyhome.recipe.biz.mapper",
        "com.familyhome.vault.biz.dao",
        "com.familyhome.user.biz.dao"
})
public class FamilyHomeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FamilyHomeApplication.class, args);
    }
}
