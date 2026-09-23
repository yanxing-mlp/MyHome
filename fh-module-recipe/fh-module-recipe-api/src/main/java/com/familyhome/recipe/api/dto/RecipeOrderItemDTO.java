package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 订单明细 DTO：菜名与做法都是下单时的快照，菜品之后改名/删除不影响这里。
 */
@Data
public class RecipeOrderItemDTO {

    private Long recipeId;

    /**
     * 下单时的菜名快照
     */
    private String recipeName;

    /**
     * 下单时的封面图 URL 快照，NULL=这道菜当时没图（或这单早于该字段上线）
     */
    private String coverUrl;

    private Integer qty;

    /**
     * 所选做法，NULL/空 = 未选
     */
    private List<CartPracticeDTO> practices;
}
