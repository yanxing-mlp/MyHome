package com.familyhome.file.biz.controller.b;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.FileGroupVO;
import com.familyhome.file.biz.service.FileGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * 文件/视频分组 B 端接口（方案 §5.3）。
 *
 * <p>一张表服务两类分组，靠必传的 {@code bizType}（{@code document}/{@code video}）区分——B 端"文件管理"
 * 传 {@code document}、"视频管理"传 {@code video}，两页各自一套分组，互不串。分区仍走 {@code scope}
 * （默认 PUBLIC），与文档/视频列表同一口径：私人分组只落当前账号，ADMIN 无例外，跨区裸 id 一律 404。
 *
 * <p>分组是硬删除，删组时数据库外键级联清关联行；文件的分组归属由 {@code PUT /files/{fileId}} 整组覆盖。
 */
@Validated
@RestController
@RequestMapping("/api/b/file/groups")
@RequiredArgsConstructor
public class FileGroupController {

    private final FileGroupService groupService;

    /** 某类分组的全量列表，不分页，带组内文件数。 */
    @GetMapping
    public Result<List<FileGroupVO>> list(
            @RequestParam("bizType") String bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(groupService.list(bizType, keyword, scope));
    }

    /** 新建分组，同分区同类重名返回 409。 */
    @PostMapping
    public Result<Long> create(
            @RequestBody @Valid CreateRequest request,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(groupService.create(bizType, request.getName(), scope));
    }

    /** 改名（分组没有上下架，只有名字可改）。 */
    @PutMapping("/{id}")
    public Result<Void> update(
            @PathVariable Long id,
            @RequestBody @Valid RenameRequest request,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        groupService.update(id, request.getName(), bizType, scope);
        return Result.ok();
    }

    /** 拖拽后整批重排，整批校验后才写入。 */
    @PutMapping("/sort")
    public Result<Void> batchUpdateSort(
            @RequestBody @Valid BatchSortRequest request,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        groupService.batchUpdateSort(request.getItems(), bizType, scope);
        return Result.ok();
    }

    /** 删除分组，关联行由外键级联清掉。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        groupService.delete(id, bizType, scope);
        return Result.ok();
    }

    /** 整组覆盖一个文件/视频的分组归属；空数组即"移出所有分组"（回到未分组，只出现在「全部」里）。 */
    @PutMapping("/files/{fileId}")
    public Result<Void> setFileGroups(
            @PathVariable Long fileId,
            @RequestBody @Valid SetGroupsRequest request,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        groupService.setFileGroups(fileId, request.getGroupIds(), bizType, scope);
        return Result.ok();
    }

    // ========== 请求 DTO ==========

    @Data
    public static class CreateRequest {
        @NotBlank(message = "分组名不能为空")
        @Size(max = 64, message = "分组名最多 64 个字")
        private String name;
    }

    @Data
    public static class RenameRequest {
        @NotBlank(message = "分组名不能为空")
        @Size(max = 64, message = "分组名最多 64 个字")
        private String name;
    }

    @Data
    public static class BatchSortRequest {
        @Valid
        private List<FileGroupService.SortItem> items;
    }

    @Data
    public static class SetGroupsRequest {
        private List<Long> groupIds;
    }
}
