package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 菜谱查询请求（方案 §5.6）。
 */
@Data
public class RecipeQueryRequest {

    /**
     * 页码（从 1 开始）
     */
    @Min(value = 1, message = "页码必须大于等于 1")
    private Integer pageNo = 1;

    /**
     * 每页大小
     */
    @Min(value = 1, message = "每页大小必须大于等于 1")
    @Max(value = 100, message = "每页大小不得超过 100")
    private Integer pageSize = 20;

    /**
     * 关键词（菜名模糊搜索）
     */
    private String keyword;

    /**
     * 分类 ID（单选）
     */
    private Long categoryId;

    /**
     * 状态筛选
     */
    private String status;
}
