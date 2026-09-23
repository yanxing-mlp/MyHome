package com.familyhome.recipe.biz.controller.b;

import com.familyhome.common.result.PageResult;
import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.*;
import com.familyhome.recipe.biz.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 菜谱 B 端接口（方案 §5.6）。
 */
@Validated
@RestController
@RequestMapping("/api/b/recipe")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService recipeService;

    /**
     * 分页查询菜谱列表（B 端菜谱管理页：可下架、可搜索、可按分类筛，所以要翻页）。
     *
     * <p>C 端点餐页读的是 {@code GET /api/c/recipe/recipes}：那一份在服务端写死只查在架、一次给全量，
     * 不需要 {@code status}/{@code pageNo} 这些参数。
     */
    @GetMapping("/recipes")
    public Result<PageResult<RecipeDTO>> pageRecipes(@Validated RecipeQueryRequest request) {
        return Result.ok(recipeService.pageRecipes(request));
    }

    /**
     * 创建菜谱。
     */
    @PostMapping("/recipes")
    public Result<Long> createRecipe(@RequestBody @Valid CreateRecipeRequest request) {
        return Result.ok(recipeService.createRecipe(request));
    }

    /**
     * 更新菜谱。
     */
    @PutMapping("/recipes/{id}")
    public Result<Void> updateRecipe(@PathVariable Long id, @RequestBody @Valid UpdateRecipeRequest request) {
        recipeService.updateRecipe(id, request);
        return Result.ok();
    }

    /**
     * 删除菜谱（软删）。
     */
    @DeleteMapping("/recipes/{id}")
    public Result<Void> deleteRecipe(@PathVariable Long id) {
        recipeService.deleteRecipe(id);
        return Result.ok();
    }

    /**
     * 获取菜谱详情。
     */
    @GetMapping("/recipes/{id}")
    public Result<RecipeDTO> getRecipeDetail(@PathVariable Long id) {
        return Result.ok(recipeService.getRecipeDetail(id));
    }
}
