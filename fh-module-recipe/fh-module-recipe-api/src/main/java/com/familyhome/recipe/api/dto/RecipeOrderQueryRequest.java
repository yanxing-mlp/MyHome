package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 点单列表查询请求（分页 + 条件过滤）。
 *
 * <p>字段与 {@link RecipeQueryRequest} 同一套口径：页码从 1 起、单页上限 100，
 * 过滤条件为空就是"不加这个条件"，不做"空字符串等于查全部"这种特殊值。
 */
@Data
public class RecipeOrderQueryRequest {

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
     * 状态筛选：PENDING / COMPLETED / CANCELLED，三档之外一律查不出东西（不给特殊值）
     */
    private String status;

    /**
     * 关键词：按订单明细里的菜名快照模糊匹配，一单中任意一道菜命中即命中这一单
     */
    private String keyword;
}
