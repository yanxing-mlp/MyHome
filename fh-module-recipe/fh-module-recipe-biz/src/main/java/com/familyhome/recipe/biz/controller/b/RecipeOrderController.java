package com.familyhome.recipe.biz.controller.b;

import com.familyhome.common.result.PageResult;
import com.familyhome.common.result.Result;
import com.familyhome.recipe.api.dto.RecipeOrderDTO;
import com.familyhome.recipe.api.dto.RecipeOrderQueryRequest;
import com.familyhome.recipe.api.dto.RecipeOrderStatDTO;
import com.familyhome.recipe.api.dto.RecipeOrderStatQueryRequest;
import com.familyhome.recipe.biz.service.RecipeOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 点餐订单 B 端接口（方案 §5.3）。
 *
 * <p>只有 C 端要的那几条不在这：下单、再来一单、继续加菜，以及"一次看全"的列表与统计，
 * 全在 {@code /api/c/recipe/orders/**}。这一份留给 B 端的是点单列表（分页 + 按状态/菜名筛）、
 * 点单统计页（同样分页 + 菜名筛）、订单详情、改档与删除。
 *
 * <p>状态改档两端各走自己那条路径：推进到已完成、取消订单两端都出按钮。两个动作都只从待制作推得过去，
 * 已完成与已取消都是定稿档，不给回头路。
 *
 * <p>删除只有 B 端给（{@code DELETE /{id}}），而且只删得掉那两个定稿档——历史清不清是管理人决定的事，
 * C 端不提供入口。
 */
@RestController
@RequestMapping("/api/b/recipe/orders")
@RequiredArgsConstructor
public class RecipeOrderController {

    private final RecipeOrderService orderService;

    /**
     * 订单列表：分页 + 条件过滤（`status` 精确、`keyword` 按明细菜名快照模糊），最近下单的在前，直接带上明细。
     *
     * <p>B 端点单列表按页翻；C 端"我的订单"走 {@code GET /api/c/recipe/orders}，那一份不分页、不给筛选项。
     */
    @GetMapping
    public Result<PageResult<RecipeOrderDTO>> pageOrders(@Validated RecipeOrderQueryRequest request) {
        return Result.ok(orderService.pageOrders(request));
    }

    /**
     * 订单详情。/{id} 与下面的 /statistics 不冲突：字面量路径的匹配优先级高于路径变量。
     */
    @GetMapping("/{id}")
    public Result<RecipeOrderDTO> getOrder(@PathVariable Long id) {
        return Result.ok(orderService.getOrder(id));
    }

    /**
     * 推进到已完成（PENDING → COMPLETED）。已是已完成的按幂等返回成功。
     *
     * <p>这一档是定稿：完成之后不能再改回待制作，明细也就此锁死（"继续加菜"只认待制作）。
     */
    @PostMapping("/{id}/complete")
    public Result<Void> completeOrder(@PathVariable Long id) {
        orderService.completeOrder(id);
        return Result.ok();
    }

    /**
     * 取消订单（PENDING → CANCELLED），C 端与 B 端都给这一档。
     *
     * <p>只有待制作的单能取消，已完成的单一律业务报错；已取消按幂等返回成功。
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancelOrder(id);
        return Result.ok();
    }

    /**
     * 删除订单：整单连同明细物理删掉，累计份数一并少掉这一单。
     *
     * <p>只有定稿的两档可以删（已完成 / 已取消）；待制作的单一律报错，要它消失走 {@code /cancel}。
     * 明细里的封面只是 URL 快照，图片文件归菜谱域，所以这里不碰物理文件。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return Result.ok();
    }

    /**
     * 点单统计：每个菜品的累计下单份数（分页 + 菜名关键词过滤，份数多的在前）。
     *
     * <p>B 端统计页按页翻；C 端点餐页要的是所有菜品的份数，走 {@code GET /api/c/recipe/orders/statistics}，
     * 那一份直接给全量。两处的聚合是同一次实现，数字不会两端各算一遍。
     */
    @GetMapping("/statistics")
    public Result<PageResult<RecipeOrderStatDTO>> statistics(@Validated RecipeOrderStatQueryRequest request) {
        return Result.ok(orderService.pageStatistics(request));
    }
}
