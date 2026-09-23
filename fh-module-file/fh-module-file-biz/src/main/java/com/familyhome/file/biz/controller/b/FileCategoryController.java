package com.familyhome.file.biz.controller.b;

import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.FileCategoryVO;
import com.familyhome.file.biz.service.FileCategoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件分类字典 B 端接口（方案 §5.3）。
 *
 * <p>入口只有"列 + 新建"，新建挂在文件管理页的分类下拉框里（内联新建），没有管理页。
 */
@Validated
@RestController
@RequestMapping("/api/b/file/categories")
@RequiredArgsConstructor
public class FileCategoryController {

    private final FileCategoryService categoryService;

    /** 当前分区的分类，不分页 */
    @GetMapping
    public Result<List<FileCategoryVO>> list(
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(categoryService.list(DataPartition.forRequest(scope)));
    }

    /** 新建分类，同分区重名返回 409；body 仍只接收 name。 */
    @PostMapping
    public Result<Long> create(
            @RequestBody @Valid CreateRequest request,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(categoryService.create(request.getName(), DataPartition.forRequest(scope)));
    }

    @Data
    public static class CreateRequest {

        @NotBlank(message = "请输入分类名称")
        @Size(max = 32, message = "分类名称最多 32 个字")
        private String name;
    }
}
