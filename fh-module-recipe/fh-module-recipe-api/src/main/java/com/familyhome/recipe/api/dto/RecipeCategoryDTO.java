package com.familyhome.recipe.api.dto;

import lombok.Data;

/**
 * 菜谱分类 DTO。
 */
@Data
public class RecipeCategoryDTO {

    private Long id;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 排序权重
     */
    private Integer sortOrder;

    /**
     * 关联菜品数量
     */
    private Integer recipeCount;

    /**
     * 添加人（{@code app_user.id}），昵称由前端从账号字典 {@code user/options} 解析（两端各一条路径）
     */
    private Long creatorId;
}
