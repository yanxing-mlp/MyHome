package com.familyhome.album.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批量绑定图片到分组的请求项。
 *
 * <p>对应 {@code POST /api/b/album/groups/{groupId}/images} 的 body 数组元素。
 * lng/lat/shootTime 由前端提取后传来，仅创建本分区图片行时保存，复用行不覆盖元数据。
 */
@Data
public class AlbumImageBindRequest {

    /** 文件 ID */
    private Long fileId;

    /** 城市，自由文本 */
    private String city;

    /** GPS 经度 */
    private BigDecimal lng;

    /** GPS 纬度 */
    private BigDecimal lat;

    /** 拍摄时间 */
    private LocalDateTime shootTime;
}
