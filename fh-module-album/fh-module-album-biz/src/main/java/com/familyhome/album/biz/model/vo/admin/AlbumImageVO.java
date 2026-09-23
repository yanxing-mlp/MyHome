package com.familyhome.album.biz.model.vo.admin;

import com.familyhome.common.enums.ContentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 相册图片的 B 端视图。
 *
 * <p>这里<b>不直接存 url / thumbUrl</b>——URL 由 Service 层从 {@code FileFacade.mapByIds()}
 * 拿到的 FileDTO 里组装（方案 §3.2 约束 2）。
 */
@Getter
@Setter
public class AlbumImageVO {

    private Long id;

    /**
     * 当前分区内所属分组 ID，可以多个；空列表表示未分组。
     * 只有 FAMILY 未分组图片会展示到 C 端「其他」卡。
     */
    private List<Long> groupIds;

    /** 文件 ID */
    private Long fileId;

    /** 原图 URL */
    private String url;

    /** 缩略图 URL */
    private String thumbUrl;

    /** 城市 */
    private String city;

    /** GPS 经度 */
    private BigDecimal lng;

    /** GPS 纬度 */
    private BigDecimal lat;

    /** 拍摄时间 */
    private LocalDateTime shootTime;

    /** 状态：ON_SHELF / OFF_SHELF / DELETED */
    private ContentStatus status;

    /** 1=置顶 */
    private Integer pinned;

    /** 上传人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    /** 图片宽度 */
    private Integer width;

    /** 图片高度 */
    private Integer height;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
