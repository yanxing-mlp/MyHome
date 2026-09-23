package com.familyhome.album.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 相册图片关联记录，表 {@code album_image}。
 *
 * <p><b>三态状态</b>：{@code status} 用枚举 {@code ContentStatus}（ON_SHELF/OFF_SHELF/DELETED），
 * 所有查询必须手写 {@code status <> 'DELETED'} 过滤（方案 §10 风险 5）。
 *
 * <p><b>置顶语义</b>：{@code pinned} 是布尔（0/1），不是 sort 值。排序规则：
 * {@code pinned DESC, create_time DESC, id DESC} —— 置顶的排最前，同层按上传时间倒序（方案 §0）。
 *
 * <p><b>这里没有 group_id</b>：一张图可以挂多个分组，成员关系唯一存在
 * {@code album_image_group_rel}（V202 建表、V206 回填历史缺口、V207 删掉遗留的 {@code group_id} 列）。
 * 想知道一张图属于哪些分组，查关联表，见 {@code AlbumImageService#mapGroupIdsByImageIds}。
 */
@Getter
@Setter
@TableName("album_image")
public class AlbumImageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 图片文件 ID；历史分区拆分的图片可共享不可变文件，删除前须确认没有活引用 */
    private Long fileId;

    /** 不可修改的数据分区 */
    private AlbumScope scope;

    /** FAMILY 恒为 0；PERSONAL 为所属账号，不能以 creatorId 代替 */
    private Long ownerId;

    /** 城市，自由文本 */
    private String city;

    /** GPS 经度，一期只存不用 */
    private BigDecimal lng;

    /** GPS 纬度，一期只存不用 */
    private BigDecimal lat;

    /** 拍摄时间，取 EXIF；无则等于 create_time */
    private LocalDateTime shootTime;

    /** 三态状态：ON_SHELF / OFF_SHELF / DELETED */
    private ContentStatus status;

    /** 1=分组内置顶 */
    private Integer pinned;

    /**
     * 上传人（{@code app_user.id}），V209 加列。
     *
     * <p>同分区复用图片不会改写上传人；新行读取 FileDTO.creatorId。
     * 历史跨分区拆分也保留上传人，个人属主以 ownerId 为准。
     */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
