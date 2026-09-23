package com.familyhome.album.biz.model.vo.admin;

import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 相册分组的 B 端视图：名字、排序、状态、张数和时间。
 *
 * <p>B 端分组页不展示封面图，所以这里没有封面字段；带封面的是 C 端相册页那张叠图卡片
 * （{@code vo/client.AlbumGroupCoverVO}，最多 3 张）。
 *
 * <p>{@code status} 只给 B 端自己用：那张卡上的上下架开关读它。C 端上传浮层的分组候选
 * <b>不读这一份</b>，走 {@code GET /api/c/album/groups/options}，那一端点在服务端就把下架分组滤掉了；
 * C 端相册页的卡片列表走 {@code GET /api/c/album/groups/covers}，后端本来就只给上架分组。
 */
@Getter
@Setter
public class AlbumGroupVO {

    private Long id;

    /** 分组名 */
    private String name;

    /** 排序值，越大越靠前 */
    private Integer sort;

    /** 三态里的两态会出现在 B 端列表：ON_SHELF / OFF_SHELF（DELETED 后端已过滤） */
    private ContentStatus status;

    /**
     * 家庭 / 个人（V210）。B 端列表的默认口径是"家庭全部 + 我自己的个人相册"，所以这一份里
     * 两种都有——图片管理页的分组筛选、图片编辑弹窗的"改分组"都要靠它把个人相册标出来，
     * 否则改分组那一格会把看不见的私人相册名当成分组号显示，且整组覆盖时会静默解掉那条关联。
     */
    private AlbumScope scope;

    /** 图片数量（含已下架，不含已删除）*/
    private Integer imageCount;

    /** 添加人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
