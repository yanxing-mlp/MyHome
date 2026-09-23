package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 点餐订单 DTO（列表与详情共用一个结构）。
 *
 * <p>家庭场景单量小，列表也直接带明细，C 端列表页拼"菜品摘要"就不用再请求一次。
 */
@Data
public class RecipeOrderDTO {

    private Long id;

    /**
     * 订单状态：PENDING=待制作 / COMPLETED=已完成。
     * 完成时刻不外露（{@code update_time} 即它，但前端只展示下单时间），所以 DTO 不带该字段。
     */
    private String status;

    /**
     * 合计份数（下单时的购物车快照）
     */
    private Integer totalQty;

    /**
     * 下单人（{@code app_user.id}），两端都显示成"下单人：昵称"，
     * 昵称由前端从账号字典 {@code user/options} 解析（两端各一条路径）。
     */
    private Long creatorId;

    private LocalDateTime createTime;

    /**
     * 订单明细（按下单时的加入顺序）
     */
    private List<RecipeOrderItemDTO> items;
}
