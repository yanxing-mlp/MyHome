package com.familyhome.album.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 相册图片-分组关联记录，表 {@code album_image_group_rel}。
 *
 * <p>支持一张图片属于多个分组（多对多关系）。
 * 城市信息仍存储在 {@code album_image.city}，每个图片只有一个城市。
 */
@Getter
@Setter
@TableName("album_image_group_rel")
public class AlbumImageGroupRelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 相册图片 ID */
    private Long imageId;

    /** 相册分组 ID */
    private Long groupId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
