package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 家庭共享购物车快照：版本与全部条目在同一事务、同一把购物车锁内读取。
 */
@Data
public class CartSnapshotDTO {

    private Long version;

    private List<CartItemDTO> items;
}
