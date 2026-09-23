package com.familyhome.album.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 相册分组记录，表 {@code album_group}。
 *
 * <p><b>三态状态</b>（V208 起）：与 {@code album_image}、{@code recipe} 同一个
 * {@code ContentStatus}（ON_SHELF/OFF_SHELF/DELETED）。分组这一档比图片多一个作用域：
 * <b>下架是"整本相册对 C 端消失"</b>，封面卡片、详情深链、C 端上传的分组候选三处一起没有它，
 * 但 B 端列表照旧出这一行（张数也照旧数），不然下架之后就没法再把它上架回来。
 * 下架<b>不</b>连带下架组里的图片——图片各自的 status 不变，所以一张同时挂在两个分组的图，
 * 另一个分组那张卡照旧在。
 *
 * <p>这个列以前是 {@code deleted TINYINT} + {@code @TableLogic}（两态，分组没有"下架"语义），
 * 加下架时没有保留它再并排加一列：一行上两套删除过滤机制容易漏配，漏一边就把已删分组当下架分组列出来。
 * 换成三态后 {@code @TableLogic} 用不了（"未删除"有两个值），
 * <b>所有查询必须手写 {@code status <> 'DELETED'}，删分组也只写 {@code status = DELETED}</b>——
 * 少写一处就把已删的分组又列回 B 端，见方案 §10 风险 5。
 */
@Getter
@Setter
@TableName("album_group")
public class AlbumGroupDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组名，如 2026 春节 */
    private String name;

    /**
     * 排序值，越大越靠前。
     *
     * <p>新建时取 {@code max(sort) + 1}；拖拽排序后批量全量重排提交（方案 §7.3）。
     */
    private Integer sort;

    /** 三态状态：ON_SHELF / OFF_SHELF / DELETED（下架=整本相册对 C 端隐藏，B 端仍可见） */
    private ContentStatus status;

    /**
     * 归属范围：FAMILY 家庭相册 / PERSONAL 个人相册（V210 加列，存量全部落 FAMILY）。
     *
     * <p>个人分组属主就是 {@link #creatorId}，B/C 端读写均须校验当前账号；
     * 图片与城市另存 scope/owner_id，不能跨分区关联，scope 创建后不可改。
     */
    private AlbumScope scope;

    /** 添加人（{@code app_user.id}），V209 加列；存量由 V501 洗成大宝。PERSONAL 行里它的含义是属主 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
