package com.familyhome.user.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 全家成员，表 {@code app_user}。
 *
 * <p>口令在 {@link #passwordHash}：PBKDF2 哈希，<b>不是</b>密码本那一套可逆密文，
 * 也没有任何接口会把它返回出去（列上标了 {@code select = false}，见该字段的注释）。
 */
@Getter
@Setter
@TableName("app_user")
public class AppUserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 昵称，如 大宝 / 小宝。展示用，也是登录页下拉框的文案 */
    private String name;

    /** 手机号，个人资料项（V502 起不再是登录核对项）；展示给管理员与本人，C 端不显示 */
    private String phone;

    /**
     * 登录口令的 PBKDF2 哈希，格式见 V502 迁移。
     *
     * <p>{@code select = false}：MyBatis-Plus 生成的所有查询都<b>不取这一列</b>，
     * 于是 {@code selectById} / 列表 / 任何 Wrapper 查询天然拿不到它——"别把口令带出 DAO"
     * 由框架保证而不是靠人 review（与 {@code vault_account.password_enc} 同一手法）。
     * 唯一读到它的地方是 {@code AppUserMapper#selectPasswordHashById} 那条显式 SQL，
     * 而那一步的产物只进 {@code UserPasswordManager#matches}，不外传。
     *
     * <p>写入照常：{@code select = false} 只管查询列表，insert / updateById 都会带上这一列。
     */
    @TableField(value = "password_hash", select = false)
    private String passwordHash;

    /** 头像，指向 {@code file_object.id}（biz_type = USER_AVATAR）。null = 用前端默认头像 */
    private Long avatarFileId;

    /** ADMIN / MEMBER，口径见 {@code CurrentUserHolder} 的常量 */
    private String role;

    // 刻意没有 creator_id：需求点名的"添加人"是订单/图片/分组/菜品/分类/文件/密码本那几处业务数据，
    // 不含账号管理页（UserAdminVO 也不返回它）。种子的大宝更是没有"谁加的"，加了就是一列没人读的死数据。

    /** 两态删除，与 {@code file_object} 同构；@TableLogic 自动过滤，见包注释。 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
