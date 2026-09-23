package com.familyhome.recipe.api.dto;

import lombok.Data;

/**
 * 购物车条目里的一个做法选择（一个分组选一个选项）。
 */
@Data
public class CartPracticeDTO {

    private Long groupId;

    private Long optionId;
}
