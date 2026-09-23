package com.familyhome.recipe.api.dto;

import lombok.Data;

/**
 * "再来一单"结果：订单明细写回购物车后的回执。
 *
 * <p>只回计数不回内容——前端拿完这个结果直接重读服务端购物车即可（与确认订单页同一口径），
 * 避免把车里的数据再传一遍造成两端不一致。
 */
@Data
public class ReorderResultDTO {

    /**
     * 实际加入购物车的明细条数（同菜累加后仍算一条）
     */
    private Integer addedCount;

    /**
     * 跳过的条数：菜品已被删除（status=DELETED），历史快照里的菜不可能再加回来，只计数不报错
     */
    private Integer skippedCount;
}
