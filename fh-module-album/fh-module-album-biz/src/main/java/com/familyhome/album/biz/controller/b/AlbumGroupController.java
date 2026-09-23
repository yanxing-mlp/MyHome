package com.familyhome.album.biz.controller.b;

import com.familyhome.album.api.dto.AlbumImageBindRequest;
import com.familyhome.album.biz.model.vo.admin.AlbumGroupVO;
import com.familyhome.album.biz.service.AlbumGroupService;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 相册分组 B 端接口（方案 §5.3）。
 */
@Validated
@RestController
@RequestMapping("/api/b/album/groups")
@RequiredArgsConstructor
public class AlbumGroupController {

    private final AlbumGroupService groupService;

    /**
     * 全量列表，不分页（含下架分组，不含已删除；下架的那几行 {@code status=OFF_SHELF}）。
     *
     * <p><b>只有 B 端消费</b>：相册分组管理页、个人相册页、图片管理页的分组筛选、图片编辑弹窗的改分组。
     * C 端两处都不读它——相册页的卡片走 {@code GET /api/c/album/groups/covers}（按 scope 只给可见上架分组），
     * 上传浮层的分组候选走 {@code GET /api/c/album/groups/options}（同样只给上架，但含没有在架图的空分组，
     * 因为正好要往里传第一批图）。
     *
     * <p>scope 默认 FAMILY，只列家庭档；PERSONAL 只列当前账号自己的分组，缺身份返回 401。
     * 所有裸 ID、排序及绑定同样按请求 scope 校验分区与属主。
     */
    @GetMapping
    public Result<List<AlbumGroupVO>> list(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) AlbumScope scope) {
        return Result.ok(groupService.list(keyword, scope));
    }

    /**
     * 新建分组。
     *
     * <p>{@code scope} 不传即家庭相册（与 V210 之前完全同行为）；传 {@code PERSONAL} 就是从
     * 「个人相册」那一页建的私人相册，属主是请求头里那个账号。
     */
    @PostMapping
    public Result<Long> create(@RequestBody @Valid CreateRequest request) {
        return Result.ok(groupService.create(request.getName(), request.getScope()));
    }

    /** 改名 / 改上下架（两个字段各自判 null，只发一个不动另一个）*/
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid UpdateGroupRequest request,
                               @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        groupService.update(id, request.getName(), request.getStatus(), scope);
        return Result.ok();
    }

    /** 批量改排序，整批校验后才写入。 */
    @PutMapping("/sort")
    public Result<Void> batchUpdateSort(@RequestBody @Valid BatchSortRequest request,
                                        @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        groupService.batchUpdateSort(request.getItems(), scope);
        return Result.ok();
    }

    /** 删除分组，级联本分区图片；共享文件还有活引用时保留。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        groupService.delete(id, scope);
        return Result.ok();
    }

    /**
     * 批量绑定图片到分组（同分组内 md5 查重）。
     *
     * <p>C 端相册页的上传浮层调的是同一服务方法的另一条路径
     * （{@code POST /api/c/album/groups/{groupId}/images}），入参元素同为 {@link AlbumImageBindRequest}：
     * 两端各自一条路径，但"一张图一行 + N 条关联"的落库口径只有一处实现。
     */
    @PostMapping("/{groupId}/images")
    public Result<AlbumGroupService.BatchBindResult> bindImages(
            @PathVariable Long groupId,
            @RequestBody @Valid BindImagesRequest request,
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(groupService.bindImages(groupId, request.getItems(), scope, false));
    }

    // ========== 请求 DTO ==========

    @Data
    public static class CreateRequest {
        @NotBlank(message = "分组名不能为空")
        private String name;

        /** 家庭 / 个人；null 按 {@code FAMILY} 处理 */
        private AlbumScope scope;
    }

    /**
     * 改名 / 上下架的入参：两个字段都可以不传，各自判 null（同 {@code UpdateImageRequest} 的 city/status）。
     *
     * <p>B 端那个开关只写 {@code ON_SHELF} / {@code OFF_SHELF}。{@code DELETED} 不从这条路走：
     * 删分组要连带删组内图片和物理文件（{@code DELETE /{id}}），只改一个字段到不了那一步。
     */
    @Data
    public static class UpdateGroupRequest {
        /** 分组名；null 表示不改名 */
        private String name;

        /** 上下架状态；null 表示不改状态 */
        private ContentStatus status;
    }

    @Data
    public static class BatchSortRequest {
        @Valid
        private List<AlbumGroupService.SortItem> items;
    }

    @Data
    public static class BindImagesRequest {
        @Valid
        private List<AlbumImageBindRequest> items;
    }
}
