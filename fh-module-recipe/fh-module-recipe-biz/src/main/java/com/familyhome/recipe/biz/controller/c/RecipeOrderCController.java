package com.familyhome.recipe.biz.controller.c;

import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.CartVersionRequest;
import com.familyhome.recipe.api.dto.RecipeOrderDTO;
import com.familyhome.recipe.api.dto.RecipeOrderStatDTO;
import com.familyhome.recipe.api.dto.ReorderResultDTO;
import com.familyhome.recipe.biz.service.RecipeOrderService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点餐订单 C 端接口（方案 §5.4）：下单、我的订单、订单详情、状态流转与"点过 x 次"。
 *
 * <p>下单/再来一单/继续加菜这三条只有 C 端有（B 端不做点单动作），整个从 {@code /api/b/recipe/orders}
 * 搬了过来。列表与统计这里<b>不分页、不带筛选</b>：C 端"我的订单"一次看全，卡片上的"点过 x 次"要一次
 * 拿全所有菜品，所以由服务端直接给全量，而不是让前端传一个 {@code pageSize=100} 的变通值。
 * 聚合口径与 B 端统计页是同一次实现（都排除已取消的单），不会出现两端数字对不上。
 *
 * <p><b>删除订单不在这里</b>：历史清不清是管理人决定的事，只有 {@code DELETE /api/b/recipe/orders/{id}}
 * 那一条，而且只删得掉已完成/已取消这两个定稿档。C 端同样没有"改状态成待制作"这种回头路——
 * 已完成与已取消都是定稿档。
 */
@Validated
@RestController
@RequestMapping("/api/c/recipe/orders")
@RequiredArgsConstructor
public class RecipeOrderCController {

    private final RecipeOrderService orderService;

    /** 下单：请求体携带 version，消费该版购物车并清空；同版本同操作重试返回原订单 ID。 */
    @PostMapping
    public Result<Long> createOrder(@RequestBody @Valid CartVersionRequest request) {
        return Result.ok(orderService.createOrder(request.getVersion()));
    }

    /** 我的订单：全部订单，最近下单的在前，直接带上明细。 */
    @GetMapping
    public Result<List<RecipeOrderDTO>> listOrders() {
        return Result.ok(orderService.listOrders());
    }

    /** 点餐页卡片上的"点过 x 次"：全部菜品的累计份数，份数多的在前。 */
    @GetMapping("/statistics")
    public Result<List<RecipeOrderStatDTO>> statistics() {
        return Result.ok(orderService.listOrderStats());
    }

    /** 订单详情；订单不存在时返回业务错（"订单不存在"）。/{id} 与上面的 /statistics 不冲突。 */
    @GetMapping("/{id}")
    public Result<RecipeOrderDTO> getOrder(@PathVariable Long id) {
        return Result.ok(orderService.getOrder(id));
    }

    /** 推进到已完成（PENDING → COMPLETED）。已是已完成的按幂等返回成功。 */
    @PostMapping("/{id}/complete")
    public Result<Void> completeOrder(@PathVariable Long id) {
        orderService.completeOrder(id);
        return Result.ok();
    }

    /** 取消订单（PENDING → CANCELLED）。只有待制作的单能取消，已完成的单一律业务报错。 */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancelOrder(id);
        return Result.ok();
    }

    /** 再来一单：把这单的明细追加回购物车（同一道菜份数累加，菜品已删除的跳过并计入回执）。 */
    @PostMapping("/{id}/again")
    public Result<ReorderResultDTO> reorder(@PathVariable Long id) {
        return Result.ok(orderService.reorderOrder(id));
    }

    /** 继续加菜：请求体携带 version，消费该版购物车并清空；同版本同目标重试不重复加菜。 */
    @PostMapping("/{id}/append")
    public Result<Void> appendCart(@PathVariable Long id, @RequestBody @Valid CartVersionRequest request) {
        orderService.appendCartToOrder(id, request.getVersion());
        return Result.ok();
    }
}
