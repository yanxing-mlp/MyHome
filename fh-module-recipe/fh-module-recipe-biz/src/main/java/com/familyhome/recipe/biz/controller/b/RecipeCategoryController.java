package com.familyhome.recipe.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.CreateCategoryRequest;
import com.familyhome.recipe.api.dto.RecipeCategoryDTO;
import com.familyhome.recipe.biz.service.RecipeCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜谱分类管理接口（B 端：增删改 + 列表）。
 *
 * <p>这一份字典两端共用，C 端点餐页那个分类侧栏走 {@code GET /api/c/recipe/categories}，
 * 两条路径背后是同一个 {@code RecipeCategoryService.listAll()}。
 */
@Validated
@RestController
@RequestMapping("/api/b/recipe/categories")
@RequiredArgsConstructor
public class RecipeCategoryController {

    private final RecipeCategoryService categoryService;

    /**
     * 查询所有分类。
     */
    @GetMapping
    public Result<List<RecipeCategoryDTO>> listCategories() {
        return Result.ok(categoryService.listAll());
    }

    /**
     * 创建分类。
     */
    @PostMapping
    public Result<Long> createCategory(@RequestBody @Valid CreateCategoryRequest request) {
        return Result.ok(categoryService.createCategory(request));
    }

    /**
     * 更新分类。
     */
    @PutMapping("/{id}")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody @Valid CreateCategoryRequest request) {
        categoryService.updateCategory(id, request);
        return Result.ok();
    }

    /**
     * 删除分类。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return Result.ok();
    }
}
