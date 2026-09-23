package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 点单统计查询请求（分页 + 条件过滤）。
 *
 * <p>统计的单位是"菜品"而不是"订单"，所以这一页的筛选条件只有菜名：聚合后的每一行代表一道菜，
 * 行数就是页里的 total，不像订单列表那样能按状态分档。
 */
@Data
public class RecipeOrderStatQueryRequest {

    @Min(value = 1, message = "页码必须大于等于 1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页大小必须大于等于 1")
    @Max(value = 100, message = "每页大小不得超过 100")
    private Integer pageSize = 20;

    /**
     * 关键词：按聚合出来的菜名快照模糊匹配（不传就是全部菜品）。
     *
     * <p>匹配的是这一行<b>显示</b>的那个名字（同一道菜多条快照里字符串序最大的那条），
     * 不是 join 菜谱表取的现名——搜得到什么就应该看得见什么。
     */
    private String keyword;
}
