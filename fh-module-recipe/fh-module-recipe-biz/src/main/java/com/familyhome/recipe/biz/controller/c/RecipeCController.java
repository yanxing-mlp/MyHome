package com.familyhome.recipe.biz.controller.c;

import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.PracticeGroupDTO;
import com.familyhome.recipe.api.dto.RecipeCategoryDTO;
import com.familyhome.recipe.api.dto.RecipeDTO;
import com.familyhome.recipe.biz.service.RecipeCategoryService;
import com.familyhome.recipe.biz.service.RecipePracticeService;
import com.familyhome.recipe.biz.service.RecipeService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜谱 C 端接口：点餐页那三张脸（分类侧栏、做法字典、菜品列表）。
 *
 * <p>只读，且只给"看得见的那一份"：菜品这一条在服务端写死只查在架，所以 C 端没有 {@code status} 参数，
 * 也没有 {@code keyword}/{@code categoryId} 这些筛选项——点餐页是整张菜单一次给完再本地分组，
 * 分页与搜索留在 B 端菜谱管理页那一条（{@code GET /api/b/recipe/recipes}）。
 *
 * <p>两条字典接口（分类、做法）与 B 端同一份数据、同一套 service，只是各走各的路径：
 * C 端不拼 B 端的路径前缀，部署层才能按前缀分网段放行（方案 §8.3）。
 */
@Validated
@RestController
@RequestMapping("/api/c/recipe")
@RequiredArgsConstructor
public class RecipeCController {

    private final RecipeService recipeService;
    private final RecipeCategoryService categoryService;
    private final RecipePracticeService practiceService;

    /** 全部菜品分类（按 sortOrder 升序，后端已排好；含每类下的菜品数，侧栏用不上但同一份 DTO）。 */
    @GetMapping("/categories")
    public Result<List<RecipeCategoryDTO>> listCategories() {
        return Result.ok(categoryService.listAll());
    }

    /** 全部做法分组 + 组内选项。C 端只读这一份字典，写操作全在 B 端。 */
    @GetMapping("/practices")
    public Result<List<PracticeGroupDTO>> listPractices() {
        return Result.ok(practiceService.listAll());
    }

    /** 整张菜单：全部在架菜品，一次给完，不翻页。下架/已删除的菜这里查不到。 */
    @GetMapping("/recipes")
    public Result<List<RecipeDTO>> listRecipes() {
        return Result.ok(recipeService.listOnShelf());
    }
}
