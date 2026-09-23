package com.familyhome.album.biz.controller.b;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.album.biz.model.vo.admin.AlbumImagePageVO;
import com.familyhome.album.biz.model.vo.admin.AlbumImageVO;
import com.familyhome.album.biz.service.AlbumImageService;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 相册图片 B 端接口（方案 §5.3）。
 */
@Validated
@RestController
@RequestMapping("/api/b/album")
@RequiredArgsConstructor
public class AlbumImageController {

    private final AlbumImageService imageService;

    /**
     * 分页列表（B 端图片网格：能按城市/状态筛，也看得见下架图）。
     *
     * <p>C 端详情页那九宫格走 {@code GET /api/c/album/images}：那一份 {@code status} 在服务端写死
     * 在架，返回的 VO 也只有 url/thumbUrl 那几个字段。
     */
    @GetMapping("/images")
    public Result<AlbumImagePageVO> page(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "false") boolean ungrouped,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(value = 100, message = "每页最多 100 条") int pageSize) {
        Page<AlbumImageVO> mpPage = imageService.pageForMp(groupId, ungrouped, city, status, pageNo, pageSize, scope, false);
        
        AlbumImagePageVO result = new AlbumImagePageVO();
        result.setList(mpPage.getRecords());
        result.setTotal(mpPage.getTotal());
        result.setPageNo(mpPage.getCurrent());
        result.setPageSize((int) mpPage.getSize());
        result.setHasMore(mpPage.getCurrent() * mpPage.getSize() < mpPage.getTotal());
        
        return Result.ok(result);
    }

    /** 改城市 / 改状态 */
    @PutMapping("/images/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid UpdateImageRequest request,
                               @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        imageService.update(id, request.getCity(), request.getStatus(), scope);
        return Result.ok();
    }

    /** 置顶 / 取消置顶 */
    @PutMapping("/images/{id}/pin")
    public Result<Void> togglePin(@PathVariable Long id, @RequestBody @Valid TogglePinRequest request,
                                  @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        imageService.togglePin(id, request.isPinned(), scope);
        return Result.ok();
    }

    /** 单张删除 */
    @DeleteMapping("/images/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        imageService.delete(id, scope);
        return Result.ok();
    }

    /** 批量删除，任一 ID 非法则整批不写入。 */
    @DeleteMapping("/images")
    public Result<Void> batchDelete(@RequestBody @Valid BatchDeleteRequest request,
                                    @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        imageService.batchDelete(request.getIds(), scope);
        return Result.ok();
    }

    /** 获取本分区图片所属的分组 ID */
    @GetMapping("/images/{id}/groups")
    public Result<List<Long>> getImageGroups(@PathVariable Long id,
                                            @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(imageService.getImageGroupIds(id, scope));
    }

    /** 全部 ID 校验通过后覆盖分组，空列表也必须验证图片。 */
    @PutMapping("/images/{id}/groups")
    public Result<Void> setImageGroups(@PathVariable Long id, @RequestBody @Valid GroupIdsRequest request,
                                       @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        imageService.setImageGroups(id, request.getGroupIds(), scope);
        return Result.ok();
    }

    // ========== 请求 DTO ==========

    @Data
    public static class UpdateImageRequest {
        private String city;
        private ContentStatus status;
    }

    @Data
    public static class TogglePinRequest {
        private boolean pinned;
    }

    @Data
    public static class BatchDeleteRequest {
        private List<Long> ids;
    }

    @Data
    public static class GroupIdsRequest {
        private List<Long> groupIds;
    }
}
