package com.familyhome.file.biz.model.vo.admin;

import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件/视频分组的 B 端视图：名字、排序、类型、文件数和时间。
 *
 * <p>与相册 {@code AlbumGroupVO} 的差异：分组没有上下架（文件域用硬删除，无三态 status），
 * 所以这里没有 {@code status} 字段；多一个 {@code bizType} 区分是文件分组还是视频分组。
 * C 端视频分组卡片走 {@code VideoGroupClientVO}（裁掉 bizType/scope/时间等内部字段）。
 */
@Getter
@Setter
public class FileGroupVO {

    private Long id;

    /** 分组名 */
    private String name;

    /** 排序值，越大越靠前 */
    private Integer sort;

    /** {@code document} 文件分组 / {@code video} 视频分组 */
    private String bizType;

    private DataScope scope;

    /** 组内文件数（当前分区未删除的文档/视频） */
    private Integer fileCount;

    /** 添加人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
