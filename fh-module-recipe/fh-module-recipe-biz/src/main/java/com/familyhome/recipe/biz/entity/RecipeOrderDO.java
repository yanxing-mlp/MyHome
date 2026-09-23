package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点餐订单（家庭共用单车，下单时整单车快照）。
 */
@Data
@TableName("recipe_order")
public class RecipeOrderDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单状态：PENDING=待制作 / COMPLETED=已完成 / CANCELLED=已取消（后两档都是定稿，改不回去）。
     * 推进与取消两端都能发起，都靠条件更新保证幂等（见 {@code RecipeOrderServiceImpl}）
     */
    private String status;

    /**
     * 合计份数（下单时是购物车快照；待制作期间"继续加菜"会往上累加）
     */
    private Integer totalQty;

    /**
     * 下单人（{@code app_user.id}），V316 加列——就是需求里说的"C 端下单人"。
     *
     * <p>待制作的单后续加菜时<b>不改</b>这一列：加菜是把菜并进同一张单，下单的人还是原来那个。
     */
    private Long creatorId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
