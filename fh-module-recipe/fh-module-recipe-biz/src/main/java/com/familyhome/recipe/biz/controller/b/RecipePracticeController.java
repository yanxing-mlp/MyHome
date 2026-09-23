package com.familyhome.recipe.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.CreatePracticeGroupRequest;
import com.familyhome.recipe.api.dto.PracticeGroupDTO;
import com.familyhome.recipe.api.dto.UpdatePracticeGroupRequest;
import com.familyhome.recipe.biz.service.RecipePracticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 做法字典管理接口（分组 + 组内选项）。
 *
 * <p>只有分组这一层：编辑弹窗一次提交组名和全部选项，所以没有单个选项的增改删接口。
 * C 端只读这一份字典（详情浮层按菜品绑定的分组出选项，走 {@code GET /api/c/recipe/practices}），
 * 写操作全在 B 端。
 */
@Validated
@RestController
@RequestMapping("/api/b/recipe/practices")
@RequiredArgsConstructor
public class RecipePracticeController {

    private final RecipePracticeService practiceService;

    /**
     * 查询全部做法分组（含组内选项和关联菜品数量）。
     */
    @GetMapping
    public Result<List<PracticeGroupDTO>> listPractices() {
        return Result.ok(practiceService.listAll());
    }

    /**
     * 创建做法分组（只建分组，选项在编辑弹窗里补）。
     */
    @PostMapping
    public Result<Long> createGroup(@RequestBody @Valid CreatePracticeGroupRequest request) {
        return Result.ok(practiceService.createGroup(request));
    }

    /**
     * 更新做法分组：改组名 + 按差量覆盖组内选项。
     */
    @PutMapping("/{id}")
    public Result<Void> updateGroup(@PathVariable Long id, @RequestBody @Valid UpdatePracticeGroupRequest request) {
        practiceService.updateGroup(id, request);
        return Result.ok();
    }

    /**
     * 删除做法分组（级联删选项 + 解绑菜品）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGroup(@PathVariable Long id) {
        practiceService.deleteGroup(id);
        return Result.ok();
    }
}
