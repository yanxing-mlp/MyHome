package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车消费回执（无用户维度）。订单删除后有意保留，防止旧版本重放成单。
 */
@Data
@TableName("recipe_cart_checkout")
public class RecipeCartCheckoutDO {

    @TableId(type = IdType.INPUT)
    private Long cartVersion;

    /** 本次消费落入的订单 ID。 */
    private Long orderId;

    /** null = 新建订单；非空 = 继续加菜的目标订单 ID。 */
    private Long targetOrderId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
