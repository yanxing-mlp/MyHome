package com.familyhome.album.biz.model.vo.client;

import lombok.Getter;
import lombok.Setter;

/**
 * C 端相册详情页九宫格里的一张图。
 *
 * <p>比 B 端那份（{@code vo/admin.AlbumImageVO}）少得多：城市、经纬度、拍摄时间、宽高、上下架、
 * 置顶标记、添加人一个都不给——C 端既不展示也不编辑这些，下架的图更是根本查不到（服务端写死只看在架）。
 * 唯一的例外是 {@code id} 留着给预览浮层当下标用。
 */
@Getter
@Setter
public class AlbumImageBriefVO {

    private Long id;

    /** 文件 ID；C 端只用它做 React key */
    private Long fileId;

    /** 原图 URL（预览浮层用） */
    private String url;

    /** 缩略图 URL（九宫格用） */
    private String thumbUrl;
}
