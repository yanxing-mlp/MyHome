package com.familyhome.recipe.biz.controller.c;

import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.CartItemRequest;
import com.familyhome.recipe.api.dto.CartSnapshotDTO;
import com.familyhome.recipe.biz.service.RecipeCartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点餐购物车 C 端接口（方案 §5.4）。
 *
 * <p><b>只有 C 端有这一条</b>：购物车是"这台手机上准备下的那一单"，B 端既没有页面也不该有。
 * 原先它挂在 {@code /api/b/recipe/cart} 下被 C 端直接调用，是"一期没有 /api/c"的临时安排，现已整体搬过来。
 *
 * <p>车落在服务端（{@code recipe_cart_item}）而不是本机：家庭共用一辆车，换台手机接着点。
 */
@Validated
@RestController
@RequestMapping("/api/c/recipe/cart")
@RequiredArgsConstructor
public class RecipeCartCController {

    private final RecipeCartService cartService;

    /** 购物车版本与全部条目的同事务快照（按加入顺序）。 */
    @GetMapping
    public Result<CartSnapshotDTO> listCart() {
        return Result.ok(cartService.listCart());
    }

    /**
     * 按版本设置数量（绝对值；qty &lt;= 0 移除）；practices 整体覆盖，返回新版本快照。
     * 一人一菜一行：动的只是调用人自己那道菜的那一行，同菜别人的行不受影响。
     */
    @PutMapping
    public Result<CartSnapshotDTO> setItem(@RequestBody @Valid CartItemRequest request) {
        return Result.ok(cartService.setItem(request));
    }

    /** 按查询参数 version 清空购物车，返回新版本快照。 */
    @DeleteMapping
    public Result<CartSnapshotDTO> clearCart(
            @RequestParam("version") @NotNull(message = "购物车版本不能为空")
            @Min(value = 0, message = "购物车版本不能小于 0") Long version) {
        return Result.ok(cartService.clearCart(version));
    }
}
