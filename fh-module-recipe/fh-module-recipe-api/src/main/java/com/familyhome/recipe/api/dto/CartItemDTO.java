package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 点餐购物车条目 DTO（只回传菜品 ID + 数量 + 所选做法，菜品信息由前端列表自带）。
 */
@Data
public class CartItemDTO {

    private Long recipeId;

    private Integer qty;

    /**
     * 加购人 ID
     */
    private Long creatorId;

    /**
     * 所选做法，NULL/空 = 未选
     */
    private List<CartPracticeDTO> practices;
}
