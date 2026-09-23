package com.familyhome.vault.biz.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 密码本记录，表 {@code vault_account}。
 *
 * <p>用 {@code @Getter/@Setter} 而不是 {@code @Data}：{@code @Data} 会生成含全字段的
 * {@code toString()}，这里多一格 {@code passwordEnc} 就多一分误打日志时泄露密文的机会
 * （密文本身不该进日志，见方案 §10 风险 11）。
 */
@Getter
@Setter
@TableName("vault_account")
public class VaultAccountDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台名，如 微信 / steam / QQ */
    private String name;

    /** 账号 / 邮箱 / 手机号，明文存（本身不是机密，且要能关键词检索） */
    private String account;

    /**
     * AES-256-GCM 密文。
     *
     * <p>{@code select = false}：MyBatis-Plus 生成的所有查询都<b>不取这一列</b>，
     * 于是 selectById / 分页列表 / 任何 Wrapper 查询天然拿不到密文，
     * "别把密码带出 DAO"这条规则由框架保证而不是靠人 review。
     * 唯一能读到密文的地方是 {@code VaultAccountMapper#selectPasswordEncById} 那条显式 SQL。
     */
    @TableField(value = "password_enc", select = false)
    private String passwordEnc;

    /** 公共 / 私人分区，创建时由服务端确定，之后不可修改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DataScope scope;

    /** 公共分区为 0，私人分区为所属账号 ID；不可由请求体指定或修改。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long ownerId;

    /**
     * 真实添加人（{@code app_user.id}），创建后不再修改。
     * 可见性由 scope + ownerId 决定，creatorId 仅用于展示来源，不能用来判断权限。
     */
    private Long creatorId;

    /** 两态删除（与 {@code file_object} 同构），@TableLogic 自动过滤，见包注释。 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
