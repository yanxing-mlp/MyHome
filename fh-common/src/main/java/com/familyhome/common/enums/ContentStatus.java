package com.familyhome.common.enums;

/**
 * 内容三态状态，用于 {@code recipe}、{@code album_image} 与 {@code album_group}。
 *
 * <p><b>实现约束（方案 §10 风险 5）</b>：三态下"未删除"有 {@link #ON_SHELF} 和 {@link #OFF_SHELF}
 * 两个值，MyBatis-Plus 的 {@code @TableLogic} 只支持单一未删除值，<b>用不了</b>。
 * 所有查询必须显式带 {@code status <> 'DELETED'}，漏一处就会把已删数据查出来
 * ——对图片还是裂图，因为物理文件已经删了。
 *
 * <p>{@code album_group} 从 V208 起也挂这个枚举：它原先是两态 {@code deleted TINYINT} +
 * {@code @TableLogic}，"分组下架"要求行还在、B 端还看得见，两态表达不了，于是整列换掉
 * （没有并排留一个 {@code deleted}，一行上叠两套删除过滤机制更容易漏配）。
 * 相册域内外都只剩这一套手写条件；仍是两态 {@code deleted} 的是 {@code file_object} 与
 * {@code vault_account}（账号没有"下架"语义，刻意不改），写代码时注意别混。
 *
 * <p>存库形式：枚举名字符串（MyBatis 默认 EnumTypeHandler 行为），便于直接看库排查。
 */
public enum ContentStatus {

    /** 上架：B 端与 C 端都可见 */
    ON_SHELF,

    /** 下架：仅 B 端可见，C 端不返回 */
    OFF_SHELF,

    /**
     * 已删除：任何查询都不返回。
     *
     * <p>v4 起这个状态<b>不可恢复</b>——图片和菜谱删除时物理文件都真的从硬盘移除了，
     * 保留这条记录只是为了审计痕迹和避免主键复用，不是回收站。
     */
    DELETED;

    public boolean isDeleted() {
        return this == DELETED;
    }

    /** C 端可见性判定：只有上架内容对 C 端开放 */
    public boolean isVisibleToClient() {
        return this == ON_SHELF;
    }
}
