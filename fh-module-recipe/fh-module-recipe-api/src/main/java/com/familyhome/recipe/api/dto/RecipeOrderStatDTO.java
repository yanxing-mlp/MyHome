package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 点单统计 DTO：一个菜品的累计下单份数，以及这道菜各做法选项分别被点了多少份。
 */
@Data
public class RecipeOrderStatDTO {

    private Long recipeId;

    /**
     * 菜名快照，聚合时取 {@code MAX(recipe_name)}。
     *
     * <p>注意这不是"最近一次下单时的名字"——同一菜品改过名之后，这里落的是字符串序最大的那个快照。
     * B 端点单统计页直接展示它，C 端点餐页卡片按 {@code recipeId} 反查菜谱名，不用这个字段。
     */
    private String recipeName;

    /**
     * 累计下单份数（SUM(qty)）
     */
    private Long totalQty;

    /**
     * 菜品**当前**封面（{@code recipe_image} 里 sort 最小的那张），不是订单明细里的 {@code cover_url} 快照。
     *
     * <p>统计行是把同一道菜的多笔订单并成一行，快照封面在各行之间可能互不相同、取哪个都是任意值；
     * 而这一列回答的是"这道菜长什么样"，所以跟 {@link #recipeName} 的快照口径刻意不同。
     * 菜品删除只改状态、{@code recipe_image} 留着，所以下架/已删的菜这里一般也有值。
     * null = 这道菜没配过图，前端按默认封面兜底。
     */
    private String coverUrl;

    /**
     * 这道菜各做法选项的累计份数，份数多的在前；空 = 这道菜没被点过任何做法。
     *
     * <p>一行明细每选一个做法分组就往对应选项加一次 qty，所以这一列的份数之和与 {@link #totalQty}
     * 没有固定关系（只选了一组时相等，两组都不选时为 0）。菜品行的口径不受影响。
     */
    private List<RecipeOrderStatPracticeDTO> practices;
}
