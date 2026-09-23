package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单明细：菜名 + 做法 JSON 都是下单时的快照，菜品后续改名/删除不影响历史订单。
 */
@Data
@TableName("recipe_order_item")
public class RecipeOrderItemDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属订单 ID
     */
    private Long orderId;

    /**
     * 菜品 ID（统计口径按它聚合）
     */
    private Long recipeId;

    /**
     * 菜名快照
     */
    private String recipeName;

    /**
     * 封面图 URL 快照，NULL=下单时这道菜没有图（V315 之前建的单也没有）
     */
    private String coverUrl;

    /**
     * 份数
     */
    private Integer qty;

    /**
     * 所选做法 JSON 快照，NULL=未选
     */
    private String practices;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
