package com.familyhome.recipe.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.common.result.PageResult;
import com.familyhome.recipe.api.dto.CreateRecipeRequest;
import com.familyhome.recipe.api.dto.RecipeDTO;
import com.familyhome.recipe.api.dto.RecipeQueryRequest;
import com.familyhome.recipe.api.dto.UpdateRecipeRequest;

import java.util.List;
import java.util.Map;

/**
 * 菜谱服务（方案 §5.6）。
 */
public interface RecipeService {

    /**
     * 分页查询菜谱列表。
     * <p>
     * 注意：同维度内 OR，跨维度 AND。使用 EXISTS 子查询而非 JOIN + DISTINCT。
     */
    PageResult<RecipeDTO> pageRecipes(RecipeQueryRequest request);

    /**
     * C 端点餐页的菜单：一次给出全部在架菜品，不翻页。
     *
     * <p>与 {@link #pageRecipes} 只差在"筛选条件写死、且不分页"：下架和已删除的菜 C 端根本查不到，
     * 所以那边也没有 {@code status} 参数；家庭场景菜品量级就是个位数到几十，一次拉完比让前端
     * 传一个 {@code pageSize=100} 的变通值更诚实（B 端菜谱列表要翻页要筛选，仍走上面那个分页接口）。
     * 排序同 B 端列表的默认序（{@code update_time DESC}）。
     */
    List<RecipeDTO> listOnShelf();

    /**
     * 创建菜谱。
     */
    Long createRecipe(CreateRecipeRequest request);

    /**
     * 更新菜谱。
     */
    void updateRecipe(Long id, UpdateRecipeRequest request);

    /**
     * 删除菜谱（软删）。
     */
    void deleteRecipe(Long id);

    /**
     * 获取菜谱详情。
     */
    RecipeDTO getRecipeDetail(Long id);

    /**
     * 批量查询封面图 URL（每个菜谱取 sort 最小的那张；没图的菜不在结果里）。
     * <p>
     * 下单与继续加菜要用它把封面 URL 快照进订单明细，避免订单侧展示时再 join 菜谱表。
     */
    Map<Long, String> coverUrls(List<Long> recipeIds);
}
