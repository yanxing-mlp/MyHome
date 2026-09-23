package com.familyhome.recipe.biz.service;

import com.familyhome.common.result.PageResult;
import com.familyhome.recipe.api.dto.RecipeOrderDTO;
import com.familyhome.recipe.api.dto.RecipeOrderQueryRequest;
import com.familyhome.recipe.api.dto.RecipeOrderStatDTO;
import com.familyhome.recipe.api.dto.RecipeOrderStatQueryRequest;
import com.familyhome.recipe.api.dto.ReorderResultDTO;
import java.util.List;

/**
 * 点餐订单服务（C 端下单 + C 端订单查询与操作 + B 端点单列表与统计）。
 */
public interface RecipeOrderService {

    /**
     * 按版本把当前购物车整单快照成一条订单（状态 PENDING=待制作），清车、记回执并推进版本。
     * 同版本 create 重试返回原订单；订单已删除则报不存在，不重新消费。
     *
     * @param version 本次消费的购物车版本
     * @return 新订单 ID，或同操作重试时的原订单 ID
     */
    Long createOrder(Long version);

    /**
     * 订单列表（分页 + 条件过滤，最近下单的在前，含明细）。
     *
     * <p>过滤条件都是"没传就不加这一条"：{@code status} 按订单状态精确筛，{@code keyword} 按明细里的
     * 菜名快照模糊筛——一单里任意一道菜命中就算命中这一单（明细是快照，所以改名前的老单也能按老名字搜到）。
     *
     * <p>只有 B 端点单列表走这一条（它要翻页、要按状态和菜名筛）。C 端"我的订单"用
     * {@link #listOrders()}。
     */
    PageResult<RecipeOrderDTO> pageOrders(RecipeOrderQueryRequest request);

    /**
     * C 端"我的订单"：全部订单一次给完，最近下单的在前，含明细。
     *
     * <p>不分页的理由同 C 端菜单（{@code RecipeService#listOnShelf}）：家庭场景单量就是个位数，
     * 让前端传一个 {@code pageSize=100} 的变通值不如服务端直接给全量。排序与分页版完全一致
     * （{@code create_time DESC, id DESC}），两条路共用同一套明细装载，不会各写一遍口径。
     */
    List<RecipeOrderDTO> listOrders();

    /**
     * 订单详情；不存在时抛业务异常。
     */
    RecipeOrderDTO getOrder(Long id);

    /**
     * 把订单推进到已完成（PENDING → COMPLETED）。
     *
     * <p>已经是已完成的订单按幂等处理直接返回，不报错——前端本来就不该给这一单出按钮。
     * 这一档是定稿：明细从此不再变，也没有改回待制作这一说。
     *
     * @param id 订单 ID；不存在时抛业务异常
     */
    void completeOrder(Long id);

    /**
     * 取消订单（PENDING → CANCELLED），C 端与 B 端都有入口。
     *
     * <p>只有待制作的单能取消：已完成的单是"做完了"，取消它是改历史，一律报业务错。
     * 已取消是终态，两端都不给改回的入口，重复调用按幂等返回。
     * 明细与份数都留着，只是点单统计/"点过 x 次"不再算它。
     *
     * @param id 订单 ID；不存在或已完成时抛业务异常
     */
    void cancelOrder(Long id);

    /**
     * 删除订单：整单连同明细一起物理删掉，只有定稿的两档（已完成 / 已取消）可以删。
     *
     * <p>待制作的单子不给删——那是"还没做完的事"，要它消失该走取消那一档，删掉等于把一单没做的菜
     * 从历史里悄悄抹了。删除影响的是累计口径：明细行没了，点单统计与 C 端"点过 x 次"随之少掉这一单。
     *
     * <p>明细里的 {@code cover_url} 只是下单时的 URL 快照，图片文件归菜谱域管，所以这里
     * <b>不碰任何物理文件</b>，也就没有相册/文件那套"先软删再清盘"。
     * 消费回执有意保留，不随订单删除，防止旧购物车版本重放。
     *
     * @param id 订单 ID；不存在时抛业务异常，待制作时抛"不能删除"
     */
    void deleteOrder(Long id);

    /**
     * 再来一单：把订单明细写回当前购物车。
     *
     * <p>追加语义——车里已有的菜保留，同一道菜份数累加，做法以这单快照为准整体覆盖；
     * 菜品已删除的明细直接跳过，只通过 {@link ReorderResultDTO#getSkippedCount()} 告知。
     *
     * @param id 订单 ID；不存在或全都不可加购时抛业务异常
     */
    ReorderResultDTO reorderOrder(Long id);

    /**
     * 继续加菜：按版本把当前购物车并进这条订单，清车、记回执并推进版本。
     *
     * <p>首次消费只有待制作的单能加；不做"加菜自动重开一单"这种例外。
     * 同版本同目标重试直接成功（即使订单已定稿），原订单已删除则报不存在。
     * 合并语义与 {@link #reorderOrder(Long)} 对称——一菜一行，同一道菜累加份数、
     * 做法以本次加购整体覆盖；明细里原有的菜名、封面快照不跟着改。
     *
     * @param id 目标订单 ID
     * @param version 本次消费的购物车版本
     */
    void appendCartToOrder(Long id, Long version);

    /**
     * 点单统计：按菜品聚合累计下单份数，份数多的排前面；每道菜再带上各做法选项分别被点了多少份。
     *
     * <p>已取消的订单整体排除在外，其余状态（待制作/已完成）与菜品当前状态都不影响份数。
     * 每行另带菜品<b>当前</b>封面（{@code recipe_image}），不是明细里的 {@code cover_url} 快照。
     *
     * <p>分页与筛选都在服务端，但<b>聚合永远是对全部参与统计的明细做一次</b>——份数是全历史累加出来的，
     * 按页取明细再汇总只会得到"这一页的份数"。所以 {@code keyword} 是在聚合结果上筛显示的菜名，
     * {@code total} 是筛完的行数（= 这道菜被点过的菜品数），切片也在排好序之后。
     * 只有 B 端点单统计页走这一条；C 端"点过 x 次"用 {@link #listOrderStats()}。
     */
    PageResult<RecipeOrderStatDTO> pageStatistics(RecipeOrderStatQueryRequest request);

    /**
     * C 端点餐页的"点过 x 次"：全部菜品的累计份数一次给完，口径与 {@link #pageStatistics} 同一次聚合。
     *
     * <p>不翻页也不接受 {@code keyword}——C 端要的是给每张卡片回显一个数，筛不出的行反而会让卡片少了数字。
     */
    List<RecipeOrderStatDTO> listOrderStats();
}
