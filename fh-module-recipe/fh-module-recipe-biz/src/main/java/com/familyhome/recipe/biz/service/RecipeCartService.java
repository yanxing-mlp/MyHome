package com.familyhome.recipe.biz.service;

import com.familyhome.recipe.api.dto.CartItemRequest;
import com.familyhome.recipe.api.dto.CartSnapshotDTO;

/**
 * 点餐购物车服务（C 端点餐页加购落库，家庭共用单车）。
 */
public interface RecipeCartService {

    /**
     * 查询购物车版本与全部条目（按加入顺序），在同一事务锁内读取。
     */
    CartSnapshotDTO listCart();

    /**
     * 校验版本后设置某菜品数量（绝对值）；qty <= 0 移除条目，成功推进版本并返回新快照。
     */
    CartSnapshotDTO setItem(CartItemRequest request);

    /**
     * 校验版本后清空购物车，成功推进版本并返回新快照。
     */
    CartSnapshotDTO clearCart(Long version);
}
