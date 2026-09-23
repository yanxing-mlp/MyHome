package com.familyhome.recipe.api.dto;

import lombok.Data;

/**
 * 点单统计里的一个做法选项：这个选项在某个菜品上被点过的累计份数。
 *
 * <p>只带 ID 不带名字——统计是实时算的，名字由前端拿做法字典现查（与点单列表同一口径）。
 */
@Data
public class RecipeOrderStatPracticeDTO {

    private Long groupId;

    private Long optionId;

    /**
     * 该做法选项的累计下单份数（SUM(qty)）
     */
    private Long qty;
}
