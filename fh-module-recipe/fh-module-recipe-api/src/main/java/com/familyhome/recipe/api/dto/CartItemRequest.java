package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 设置购物车条目请求：qty 为绝对值（非增量），qty <= 0 表示移除该菜品。
 * practices 为所选做法（一个分组一个选项），整体覆盖已存做法。
 */
@Data
public class CartItemRequest {

    @NotNull(message = "购物车版本不能为空")
    @Min(value = 0, message = "购物车版本不能小于 0")
    private Long version;

    @NotNull(message = "菜品 ID 不能为空")
    private Long recipeId;

    @NotNull(message = "数量不能为空")
    private Integer qty;

    /**
     * 所选做法（可空 = 不携带做法）
     */
    private List<CartPracticeDTO> practices;
}
