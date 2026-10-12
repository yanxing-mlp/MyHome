package com.familyhome.file.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件/视频-分组关联，表 {@code file_group_rel}。支持一个文件属于多个分组（多对多）。
 *
 * <p>{@code fileId} 指向 {@code file_object.id}——文档/视频没有独立业务表（与相册
 * {@code album_image_group_rel.imageId} 指向 {@code album_image.id} 的差异就在这里）。
 *
 * <p>{@code groupId} 外键级联：分组硬删除时数据库自动清关联行。{@code fileId} 外键虽也声明级联，
 * 但删文档/视频是<b>软删</b>（{@code file_object.deleted=1} 行仍在），级联不会触发，
 * 所以服务层删文档/视频时必须显式硬删本文件的关联行（见 {@code FileGroupService#deleteFileRelations}）。
 */
@Getter
@Setter
@TableName("file_group_rel")
public class FileGroupRelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@code file_object.id}（文档或视频行） */
    private Long fileId;

    /** {@code file_group.id} */
    private Long groupId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
