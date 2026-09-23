package com.familyhome.album.biz.model.vo.client;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * C 端相册页的一张分组卡片（方案 §7.3）。
 *
 * <p>放在 {@code vo/client} 是因为它<b>只有 C 端一个消费方</b>：接口是
 * {@code GET /api/c/album/groups/covers}。卡片是"三张图叠加"的样式，所以这里带的是
 * <b>最多 3 张</b>封面缩略图 URL；B 端那张分组列表（{@code vo/admin.AlbumGroupVO}）没有封面字段。
 *
 * <p>URL 同 B 端口径：由 Service 层从 {@code FileFacade.mapByIds()} 的 FileDTO 里取
 * {@code thumbUrl} 组装，不跨域 join 文件表（方案 §3.2 约束 2）。
 */
@Getter
@Setter
public class AlbumGroupCoverVO {

    /**
     * 分组 ID；<b>"其他"这张卡为 null</b>——它不是一个真分组，而是"没关联任何分组的图片"的兜底归类。
     *
     * <p>本期卡片不可点，所以这里不需要可跳转的目标；将来要做点进分组时，"其他"得另找入口。
     */
    private Long groupId;

    /** 分组名，如 2026 春节；"其他"这张卡固定叫"其他" */
    private String name;

    /** 该分组内在架图片的张数（卡片角标"共 x 张"用这个数，不是封面那 3 张） */
    private Integer imageCount;

    /** 封面缩略图，最多 3 张，置顶图优先、其余按最新；只可能在架图为空时才会是空数组 */
    private List<String> coverThumbUrls;
}
