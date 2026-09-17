package com.familyhome.boot.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局插件。
 *
 * <p>放 fh-boot 而不是各域 biz：插件是进程级的基础设施，各域重复注册会叠加
 * （{@code MybatisPlusInterceptor} 只有一个 Bean，多个域各自 @Bean 会直接冲突）。
 *
 * <p><b>只注册分页插件</b>，两个刻意没加的：
 * <ul>
 *   <li>{@code OptimisticLockerInnerInterceptor}：本项目的口径是"家庭场景单人操作，
 *       全量覆盖/后写优先"（方案 §10 风险 10），不给表加 version 列。
 *       注意 {@code @Version} 注解在没注册这个插件时<b>完全不生效</b>，
 *       所以将来要加乐观锁必须两处一起改。</li>
 *   <li>{@code BlockAttackInnerInterceptor}（防无条件全表 update/delete）：本域所有更新删除
 *       都带 {@code id} 或 {@code group_id} 条件，注册它只是重复保险；真正防误删的是备份
 *       （方案 §10 风险 2）。</li>
 * </ul>
 *
 * <p>{@code maxLimit=100} 与 controller 上 {@code @Max(100)} 的双保险：
 * 少写一个校验的接口也不会被 {@code pageSize=100000} 拖死。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
