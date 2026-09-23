package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点餐购物车条目（家庭共用单车，无用户维度）。
 */
@Data
@TableName("recipe_cart_item")
public class RecipeCartItemDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 菜品 ID
     */
    private Long recipeId;

    /**
     * 数量，>=1；减到 0 删行
     */
    private Integer qty;

    /**
     * 所选做法 JSON（{@code [{"groupId":1,"optionId":2}]}），NULL = 未选
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
