package com.familyhome.file.biz.model.vo.client;

import lombok.Getter;
import lombok.Setter;

/**
 * 视频分组的 C 端视图（视频页的分组卡片）。
 *
 * <p>是 B 端 {@link com.familyhome.file.biz.model.vo.admin.FileGroupVO} 的裁剪版：C 端只"看"，
 * 不需要 bizType（这一端只有视频）、scope、添加人和时间。视频没有缩略图/封面（{@code thumbKey} 恒空），
 * 所以卡片只给名字 + 数量，不像相册 C 端那样叠三张图。裁剪在 {@code VideoCController} 里做。
 */
@Getter
@Setter
public class VideoGroupClientVO {

    private Long groupId;

    /** 分组名 */
    private String name;

    /** 组内视频数（当前分区未删除） */
    private Integer videoCount;
}
