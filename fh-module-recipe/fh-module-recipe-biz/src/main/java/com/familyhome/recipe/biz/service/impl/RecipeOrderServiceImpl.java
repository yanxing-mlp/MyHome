package com.familyhome.recipe.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.PageResult;
import com.familyhome.recipe.api.dto.CartPracticeDTO;
import com.familyhome.recipe.api.dto.RecipeOrderDTO;
import com.familyhome.recipe.api.dto.RecipeOrderItemDTO;
import com.familyhome.recipe.api.dto.RecipeOrderQueryRequest;
import com.familyhome.recipe.api.dto.RecipeOrderStatDTO;
import com.familyhome.recipe.api.dto.RecipeOrderStatPracticeDTO;
import com.familyhome.recipe.api.dto.RecipeOrderStatQueryRequest;
import com.familyhome.recipe.api.dto.ReorderResultDTO;
import com.familyhome.recipe.biz.entity.RecipeCartItemDO;
import com.familyhome.recipe.biz.entity.RecipeDO;
import com.familyhome.recipe.biz.entity.RecipeOrderDO;
import com.familyhome.recipe.biz.entity.RecipeOrderItemDO;
import com.familyhome.recipe.biz.mapper.RecipeCartItemMapper;
import com.familyhome.recipe.biz.mapper.RecipeMapper;
import com.familyhome.recipe.biz.mapper.RecipeOrderItemMapper;
import com.familyhome.recipe.biz.mapper.RecipeOrderMapper;
import com.familyhome.recipe.biz.service.RecipeCartState;
import com.familyhome.recipe.biz.service.RecipeOrderService;
import com.familyhome.recipe.biz.service.RecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 点餐订单服务实现。
 *
 * <p>下单与加菜的请求体只接收购物车 version，订单内容仍从服务端 {@code recipe_cart_item} 读取。
 * 先锁共享车 state，再查消费回执、校验版本；主单、明细、清车、回执、版本递增全程一个事务。
 * 同版本同操作同目标重试不重复消费；旧页面改车因版本过期失败，不能复活已消费的车。
 * 明细里的菜名、封面图和做法都是从购物车/菜谱当场取的快照，菜品之后改名、换图或删除都不影响历史订单；
 * 封面快照只在新增明细行时写入，同一道菜之后再加菜只并份数与做法，不去追改历史行。
 *
 * <p>状态有 {@code PENDING}（待制作）/ {@code COMPLETED}（已完成）/ {@code CANCELLED}（已取消）三档：
 * 待制作可以往前推成已完成（C 端、B 端都行）或取消掉（两端都行），已完成与已取消都是定稿档，
 * 一旦到了就没有回头路。改档都用条件更新：重复点同一个按钮幂等，单子停在另一档（比如已取消的想标记完成）就报错，
 * 不会假装改成功；完成时刻就是 {@code update_time}，不另开列。
 * 没有"制作中"这一档。"再来一单"是历史明细写回购物车，不直接成单；"继续加菜"方向相反，
 * 把购物车并进一条还没做完的订单，所以推进到已完成（或取消）就等于把这单锁定了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeOrderServiceImpl implements RecipeOrderService {

    /** 待制作：下单时的初始状态 */
    private static final String STATUS_PENDING = "PENDING";

    /** 已完成：两端点"已完成"推进到这里，定稿档、两端都不能改回待制作；也没有"制作中"这一档 */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 已取消：终态，只有待制作的单可以取消进来，两端都不给改回的入口 */
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final RecipeCartItemMapper cartMapper;
    private final RecipeMapper recipeMapper;
    private final RecipeOrderMapper orderMapper;
    private final RecipeOrderItemMapper orderItemMapper;
    private final RecipeService recipeService;
    private final RecipeCartState cartState;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Long createOrder(Long version) {
        Long currentVersion = cartState.lock();
        // 即使命中回执也保留原来的登录边界，不允许匿名请求通过幂等分支。
        Long creatorId = CurrentUserHolder.requireUserId();
        Long checkedOutOrderId = cartState.findCheckoutOrderId(version, null);
        if (checkedOutOrderId != null) {
            requireOrderForUpdate(checkedOutOrderId);
            return checkedOutOrderId;
        }
        cartState.checkVersion(version, currentVersion);
        List<RecipeCartItemDO> cartItems = cartMapper.selectList(
                new LambdaQueryWrapper<RecipeCartItemDO>().orderByAsc(RecipeCartItemDO::getId));
        if (cartItems.isEmpty()) {
            throw BizException.of(null, "购物车是空的，先去点两个菜吧");
        }
        Map<Long, RecipeDO> recipes = loadRecipes(cartItems);
        Map<Long, String> covers = recipeService.coverUrls(List.copyOf(recipes.keySet()));

        RecipeOrderDO order = new RecipeOrderDO();
        order.setStatus(STATUS_PENDING);
        order.setTotalQty(cartItems.stream().mapToInt(RecipeCartItemDO::getQty).sum());
        // 下单人 = 当前登录的人。C 端首页选人后由请求头带进来（见 CurrentUserInterceptor）；
        // 之后"继续加菜"不改这一列，加菜只是把菜并进同一张单。
        order.setCreatorId(creatorId);
        orderMapper.insert(order);

        for (RecipeCartItemDO item : cartItems) {
            RecipeOrderItemDO orderItem = new RecipeOrderItemDO();
            orderItem.setOrderId(order.getId());
            orderItem.setRecipeId(item.getRecipeId());
            orderItem.setRecipeName(recipes.get(item.getRecipeId()).getName());
            orderItem.setCoverUrl(covers.get(item.getRecipeId()));
            orderItem.setQty(item.getQty());
            orderItem.setPractices(item.getPractices());
            orderItemMapper.insert(orderItem);
        }

        cartMapper.delete(new LambdaQueryWrapper<>());
        cartState.recordCheckout(version, order.getId(), null);
        cartState.advance(currentVersion);
        log.info("下单: orderId={}, items={}, totalQty={}",
                order.getId(), cartItems.size(), order.getTotalQty());
        return order.getId();
    }

    @Override
    public PageResult<RecipeOrderDTO> pageOrders(RecipeOrderQueryRequest request) {
        LambdaQueryWrapper<RecipeOrderDO> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(RecipeOrderDO::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            // 菜名是明细里的快照，所以只能 EXISTS 到明细表上，不能 join 菜谱取现名；
            // {0} 走参数绑定，别拼字符串（与相册分组归属那两处 exists 同一写法）
            wrapper.exists(
                    "SELECT 1 FROM recipe_order_item i WHERE i.order_id = recipe_order.id"
                            + " AND i.recipe_name LIKE CONCAT('%', {0}, '%')",
                    request.getKeyword());
        }
        wrapper.orderByDesc(RecipeOrderDO::getCreateTime).orderByDesc(RecipeOrderDO::getId);

        Page<RecipeOrderDO> page = orderMapper.selectPage(
                new Page<>(request.getPageNo(), request.getPageSize()), wrapper);
        // 越界页码也要报真 total：报 0 会让分页器显示"共 0 单"，用户以为全空了，其实别的页还有
        return PageResult.of(toDtoList(page.getRecords()), page.getTotal(),
                request.getPageNo(), request.getPageSize());
    }

    @Override
    public List<RecipeOrderDTO> listOrders() {
        // C 端"我的订单"一次看全，没有 status/keyword 这两个筛选项（那是 B 端点单列表的活）
        List<RecipeOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapper<RecipeOrderDO>()
                .orderByDesc(RecipeOrderDO::getCreateTime)
                .orderByDesc(RecipeOrderDO::getId));
        return toDtoList(orders);
    }

    /**
     * 一批订单连明细一起换成 DTO：明细按这批订单 ID 一次取回，不给每张单各打一次库。
     */
    private List<RecipeOrderDTO> toDtoList(List<RecipeOrderDO> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RecipeOrderItemDO>> itemsByOrder = orderItemMapper.selectList(
                        new LambdaQueryWrapper<RecipeOrderItemDO>()
                                .in(RecipeOrderItemDO::getOrderId,
                                        orders.stream().map(RecipeOrderDO::getId).toList())
                                .orderByAsc(RecipeOrderItemDO::getId))
                .stream()
                .collect(Collectors.groupingBy(RecipeOrderItemDO::getOrderId));
        return orders.stream()
                .map(order -> toDto(order, itemsByOrder.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecipeOrderDTO getOrder(Long id) {
        // MySQL 默认 RR：主单与明细使用同一个一致性快照，避免读到加菜/删单的一半。
        RecipeOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
        }
        List<RecipeOrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<RecipeOrderItemDO>()
                        .eq(RecipeOrderItemDO::getOrderId, id)
                        .orderByAsc(RecipeOrderItemDO::getId));
        return toDto(order, items);
    }

    @Override
    @Transactional
    public void completeOrder(Long id) {
        // 条件更新：status 仍是待制作才改，所以重复点第二次只影响 0 行、按幂等返回，
        // 不用先查一遍再判断，也避开"两个端同时点"互相覆盖。只动 status，不碰明细与 total_qty
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<RecipeOrderDO>()
                .eq(RecipeOrderDO::getId, id)
                .eq(RecipeOrderDO::getStatus, STATUS_PENDING)
                .set(RecipeOrderDO::getStatus, STATUS_COMPLETED));
        if (rows > 0) {
            log.info("订单完成: orderId={}", id);
            return;
        }
        // 影响 0 行时不能一律当成功：三档状态下"没推动"还可能是单子在已取消那一档，
        // 那时必须报错——否则前端 toast 说"已标记完成"，库里却还是已取消，等于骗人
        RecipeOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
        }
        if (STATUS_COMPLETED.equals(order.getStatus())) {
            log.info("订单状态已是 {}，忽略: orderId={}", STATUS_COMPLETED, id);
            return;
        }
        // 已完成是定稿档，只能从待制作推过来：已取消的单同样不给改回待制作，两个方向都不开回头路
        if (STATUS_CANCELLED.equals(order.getStatus())) {
            throw BizException.of(null, "这个订单已经取消了，不能标记完成");
        }
        // 兜底：状态既不是待制作也不是已完成，说明刚被另一端改过，让他刷新再看
        throw BizException.of(null, "订单状态已经变了，刷新一下试试吧");
    }

    @Override
    @Transactional
    public void cancelOrder(Long id) {
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<RecipeOrderDO>()
                .eq(RecipeOrderDO::getId, id)
                .eq(RecipeOrderDO::getStatus, STATUS_PENDING)
                .set(RecipeOrderDO::getStatus, STATUS_CANCELLED));
        if (rows > 0) {
            log.info("订单取消: orderId={}", id);
            return;
        }
        RecipeOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
        }
        // 这里和 completeOrder 不同：已完成的单不能顺手"取消"（那是改历史），必须报错，
        // 只有本来就是已取消才按幂等返回
        if (STATUS_CANCELLED.equals(order.getStatus())) {
            log.info("订单已是已取消，忽略: orderId={}", id);
            return;
        }
        throw BizException.of(null, "这个订单已经完成了，不能取消");
    }

    @Override
    @Transactional
    public void deleteOrder(Long id) {
        // 条件删除：状态不是待制作才删得掉，所以"两个人同时点"和"刚被另一端改成待制作"都只会有一边生效。
        // 与 completeOrder/cancelOrder 同一套写法——先按条件动库，影响 0 行再回头查原因，不做"先查再删"
        int rows = orderMapper.delete(new LambdaQueryWrapper<RecipeOrderDO>()
                .eq(RecipeOrderDO::getId, id)
                .ne(RecipeOrderDO::getStatus, STATUS_PENDING));
        if (rows == 0) {
            RecipeOrderDO order = orderMapper.selectById(id);
            if (order == null) {
                throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
            }
            throw BizException.of(null, "待制作的订单不能删除，请先取消或标记完成");
        }
        // 明细跟着整单走：这张表没有软删列，留着孤儿行只会让统计口径对不上
        orderItemMapper.delete(new LambdaQueryWrapper<RecipeOrderItemDO>()
                .eq(RecipeOrderItemDO::getOrderId, id));
        // recipe_cart_checkout 回执有意保留，防止旧版本借删单重放；这里不取车锁。
        log.info("订单删除: orderId={}", id);
    }

    @Override
    @Transactional
    public ReorderResultDTO reorderOrder(Long id) {
        Long version = cartState.lock();
        RecipeOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
        }
        List<RecipeOrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<RecipeOrderItemDO>()
                        .eq(RecipeOrderItemDO::getOrderId, id)
                        .orderByAsc(RecipeOrderItemDO::getId));
        if (items.isEmpty()) {
            throw BizException.of(null, "这个订单没有菜品，没法再来一单");
        }
        // 历史快照里的菜可能已被删除：与下单同一口径（只排 DELETED），跳过而不是整单失败
        Set<Long> recipeIds = items.stream()
                .map(RecipeOrderItemDO::getRecipeId)
                .collect(Collectors.toSet());
        Set<Long> aliveIds = recipeMapper.selectBatchIds(recipeIds).stream()
                .filter(r -> !ContentStatus.DELETED.name().equals(r.getStatus()))
                .map(RecipeDO::getId)
                .collect(Collectors.toSet());
        List<RecipeOrderItemDO> validItems = items.stream()
                .filter(item -> aliveIds.contains(item.getRecipeId()))
                .toList();
        if (validItems.isEmpty()) {
            throw BizException.of(null, "这个订单的菜都已经下架删除了，没能加入");
        }

        // 追加进现有购物车：同菜累加份数，做法以本单快照整体覆盖（cart 侧同样是"不带就清空"的覆盖语义）
        Map<Long, RecipeCartItemDO> cartByRecipe = cartMapper.selectList(new LambdaQueryWrapper<>())
                .stream()
                .collect(Collectors.toMap(RecipeCartItemDO::getRecipeId, Function.identity(), (a, b) -> a));
        for (RecipeOrderItemDO item : validItems) {
            RecipeCartItemDO existing = cartByRecipe.get(item.getRecipeId());
            if (existing == null) {
                RecipeCartItemDO cartItem = new RecipeCartItemDO();
                cartItem.setRecipeId(item.getRecipeId());
                cartItem.setQty(item.getQty());
                cartItem.setPractices(item.getPractices());
                cartMapper.insert(cartItem);
                cartByRecipe.put(item.getRecipeId(), cartItem);
            } else {
                int mergedQty = existing.getQty() + item.getQty();
                cartMapper.update(null, new LambdaUpdateWrapper<RecipeCartItemDO>()
                        .eq(RecipeCartItemDO::getId, existing.getId())
                        .set(RecipeCartItemDO::getQty, mergedQty)
                        .set(RecipeCartItemDO::getPractices, item.getPractices()));
                // 同步内存态：万一同一单里有重名行（当前不会产生），累加也不会丢
                existing.setQty(mergedQty);
                existing.setPractices(item.getPractices());
            }
        }

        cartState.advance(version);
        ReorderResultDTO result = new ReorderResultDTO();
        result.setAddedCount(validItems.size());
        result.setSkippedCount(items.size() - validItems.size());
        log.info("再来一单: orderId={}, added={}, skipped={}",
                id, result.getAddedCount(), result.getSkippedCount());
        return result;
    }

    @Override
    @Transactional
    public void appendCartToOrder(Long id, Long version) {
        Long currentVersion = cartState.lock();
        Long checkedOutOrderId = cartState.findCheckoutOrderId(version, id);
        if (checkedOutOrderId != null) {
            requireOrderForUpdate(checkedOutOrderId);
            return;
        }
        cartState.checkVersion(version, currentVersion);
        // 统一顺序：先车 state，再订单当前读；与完成/取消/删除的主单写锁串行。
        RecipeOrderDO order = requireOrderForUpdate(id);
        if (!STATUS_PENDING.equals(order.getStatus())) {
            // 只有待制作的单能加菜；已完成与已取消都是定稿档，加不了，
            // 两句话分开说，免得对着一单已取消的写"已经完成了"
            throw BizException.of(null, STATUS_CANCELLED.equals(order.getStatus())
                    ? "这个订单已经取消了，不能继续加菜"
                    : "这个订单已经完成了，不能继续加菜");
        }
        List<RecipeCartItemDO> cartItems = cartMapper.selectList(
                new LambdaQueryWrapper<RecipeCartItemDO>().orderByAsc(RecipeCartItemDO::getId));
        if (cartItems.isEmpty()) {
            throw BizException.of(null, "购物车是空的，先去点两个菜吧");
        }
        // 购物车是用户当下挑的，不是历史快照，所以沿用下单那道校验：有菜被删就让他先刷新
        Map<Long, RecipeDO> recipes = loadRecipes(cartItems);
        Map<Long, String> covers = recipeService.coverUrls(List.copyOf(recipes.keySet()));

        // 一菜一行：这单里已有的菜累加份数、做法按本次覆盖；菜名与封面保留原快照，
        // 中途改名换图不去追改历史行（与"明细只读快照、不 join 菜谱"同一口径）
        Map<Long, RecipeOrderItemDO> itemsByRecipe = orderItemMapper.selectList(
                        new LambdaQueryWrapper<RecipeOrderItemDO>()
                                .eq(RecipeOrderItemDO::getOrderId, id)
                                .orderByAsc(RecipeOrderItemDO::getId))
                .stream()
                .collect(Collectors.toMap(RecipeOrderItemDO::getRecipeId, Function.identity(), (a, b) -> a));
        for (RecipeCartItemDO item : cartItems) {
            RecipeOrderItemDO existing = itemsByRecipe.get(item.getRecipeId());
            if (existing == null) {
                RecipeOrderItemDO orderItem = new RecipeOrderItemDO();
                orderItem.setOrderId(id);
                orderItem.setRecipeId(item.getRecipeId());
                orderItem.setRecipeName(recipes.get(item.getRecipeId()).getName());
                orderItem.setCoverUrl(covers.get(item.getRecipeId()));
                orderItem.setQty(item.getQty());
                orderItem.setPractices(item.getPractices());
                orderItemMapper.insert(orderItem);
            } else {
                int mergedQty = existing.getQty() + item.getQty();
                orderItemMapper.update(null, new LambdaUpdateWrapper<RecipeOrderItemDO>()
                        .eq(RecipeOrderItemDO::getId, existing.getId())
                        .set(RecipeOrderItemDO::getQty, mergedQty)
                        .set(RecipeOrderItemDO::getPractices, item.getPractices()));
                existing.setQty(mergedQty);
            }
        }

        // 总份数按"原份数 + 本次加购份数"累加，不重查明细求和：明细万一有同菜多行也不会算少
        int addedQty = cartItems.stream().mapToInt(RecipeCartItemDO::getQty).sum();
        // 已持有主单锁，完成/取消只能等待；仍保留 status 条件作防御性检查。
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<RecipeOrderDO>()
                .eq(RecipeOrderDO::getId, id)
                .eq(RecipeOrderDO::getStatus, STATUS_PENDING)
                .set(RecipeOrderDO::getTotalQty, order.getTotalQty() + addedQty));
        if (rows == 0) {
            throw BizException.of(null, "这个订单已经完成了，不能继续加菜");
        }
        cartMapper.delete(new LambdaQueryWrapper<>());
        cartState.recordCheckout(version, id, id);
        cartState.advance(currentVersion);
        log.info("订单加菜: orderId={}, addedItems={}, addedQty={}, totalQty={}",
                id, cartItems.size(), addedQty, order.getTotalQty() + addedQty);
    }

    /** 车锁之后取主单当前读；回执命中也必须确认订单仍存在，不能因删单重新创建。 */
    private RecipeOrderDO requireOrderForUpdate(Long id) {
        RecipeOrderDO order = orderMapper.selectByIdForUpdate(id);
        if (order == null) {
            throw BizException.notFound(ErrorCode.RECIPE_ORDER_NOT_FOUND, "订单不存在");
        }
        return order;
    }

    @Override
    public PageResult<RecipeOrderStatDTO> pageStatistics(RecipeOrderStatQueryRequest request) {
        // 排序、筛选、切片都排在聚合之后：份数是全历史累加出来的，按页取明细再汇总只会得到"这一页的份数"，
        // 那和分页前的数字对不上。家庭量级的菜品数量，多聚出来的行直接丢掉，成本可以忽略。
        List<RecipeOrderStatDTO> sorted = aggregateStats();
        List<RecipeOrderStatDTO> matched = sorted;
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim().toLowerCase();
            // 搜的是这一行显示的那个菜名快照（多条快照里字符串序最大的那条），不 join 菜谱取现名：
            // 搜得出来的词必须就是表格里看得见的字，否则"搜到了却看不到"。
            matched = sorted.stream()
                    .filter(stat -> stat.getRecipeName() != null
                            && stat.getRecipeName().toLowerCase().contains(keyword))
                    .toList();
        }
        int from = (request.getPageNo() - 1) * request.getPageSize();
        if (from >= matched.size()) {
            // 页码越界：list 空但 total 报筛完的真实行数，理由与 pageOrders 那处相同
            return PageResult.of(List.of(), matched.size(), request.getPageNo(), request.getPageSize());
        }
        List<RecipeOrderStatDTO> rows = List.copyOf(
                matched.subList(from, Math.min(from + request.getPageSize(), matched.size())));
        withCoverUrls(rows);
        return PageResult.of(rows, matched.size(), request.getPageNo(), request.getPageSize());
    }

    @Override
    public List<RecipeOrderStatDTO> listOrderStats() {
        List<RecipeOrderStatDTO> stats = aggregateStats();
        withCoverUrls(stats);
        return stats;
    }

    /**
     * 全量聚合出"每个菜品累计多少份 + 各做法选项多少份"，按份数倒序（同份数按菜品 ID）。
     *
     * <p>不含封面、不含菜名过滤：分页版只要被切到的那几行去查封面，C 端那份全量列表则是全部行。
     */
    private List<RecipeOrderStatDTO> aggregateStats() {
        // 一次取回参与统计的明细，菜品聚合与做法聚合都在 Java 里做。
        // 做法是 JSON 列，SQL 里 GROUP BY 不出"每个选项多少份"，分两处查又会把下面那个排除条件写两遍。
        List<RecipeOrderItemDO> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<RecipeOrderItemDO>()
                        // 已取消的单没做出来，不该算进"点过 x 次"（明细表没有状态列，按 order_id 反排）。
                        // 除此之外一律不筛：待制作/已完成都算，菜品下架删除了历史份数也照样留着。
                        .inSql(RecipeOrderItemDO::getOrderId,
                                "SELECT id FROM recipe_order WHERE status <> '" + STATUS_CANCELLED + "'")
                        .select(RecipeOrderItemDO::getRecipeId, RecipeOrderItemDO::getRecipeName,
                                RecipeOrderItemDO::getQty, RecipeOrderItemDO::getPractices));

        Map<Long, RecipeOrderStatDTO> statsByRecipe = new LinkedHashMap<>();
        // 菜品 -> (选项 -> 份数)，用 LinkedHashMap 保住"先遇到的先攒"，最后统一排序
        Map<Long, Map<Long, RecipeOrderStatPracticeDTO>> practicesByRecipe = new HashMap<>();
        for (RecipeOrderItemDO item : items) {
            RecipeOrderStatDTO stat = statsByRecipe.get(item.getRecipeId());
            if (stat == null) {
                stat = new RecipeOrderStatDTO();
                stat.setRecipeId(item.getRecipeId());
                stat.setTotalQty(0L);
                statsByRecipe.put(item.getRecipeId(), stat);
            }
            stat.setTotalQty(stat.getTotalQty() + item.getQty());
            // 菜名快照取字符串序最大的那条，与之前 SQL 的 MAX(recipe_name) 同一口径
            if (stat.getRecipeName() == null
                    || (item.getRecipeName() != null && item.getRecipeName().compareTo(stat.getRecipeName()) > 0)) {
                stat.setRecipeName(item.getRecipeName());
            }
            List<CartPracticeDTO> picks = parsePractices(item.getPractices());
            if (picks == null) {
                continue;
            }
            Map<Long, RecipeOrderStatPracticeDTO> byOption =
                    practicesByRecipe.computeIfAbsent(item.getRecipeId(), k -> new LinkedHashMap<>());
            for (CartPracticeDTO pick : picks) {
                if (pick.getOptionId() == null) {
                    continue;
                }
                RecipeOrderStatPracticeDTO practice = byOption.get(pick.getOptionId());
                if (practice == null) {
                    practice = new RecipeOrderStatPracticeDTO();
                    practice.setGroupId(pick.getGroupId());
                    practice.setOptionId(pick.getOptionId());
                    practice.setQty(0L);
                    byOption.put(pick.getOptionId(), practice);
                }
                practice.setQty(practice.getQty() + item.getQty());
            }
        }

        statsByRecipe.forEach((recipeId, stat) -> stat.setPractices(
                practicesByRecipe.getOrDefault(recipeId, Map.of()).values().stream()
                        .sorted(Comparator.comparingLong(RecipeOrderStatPracticeDTO::getQty).reversed()
                                .thenComparing(RecipeOrderStatPracticeDTO::getOptionId))
                        .toList()));

        return statsByRecipe.values().stream()
                .sorted(Comparator.comparingLong(RecipeOrderStatDTO::getTotalQty).reversed()
                        .thenComparing(RecipeOrderStatDTO::getRecipeId))
                .toList();
    }

    /**
     * 给统计行的每道菜补上封面（走菜谱当前值，与明细表的 cover_url 快照无关，见 DTO 注释）。
     * 没配过图 / 图片文件记录已丢的菜这里就是 null，前端按默认封面兜底。
     */
    private void withCoverUrls(List<RecipeOrderStatDTO> stats) {
        if (stats.isEmpty()) {
            return;
        }
        Map<Long, String> covers = recipeService.coverUrls(
                stats.stream().map(RecipeOrderStatDTO::getRecipeId).toList());
        stats.forEach(stat -> stat.setCoverUrl(covers.get(stat.getRecipeId())));
    }

    /** 一次性取出购物车涉及的菜品；有菜被删/下架说明购物车是脏的，让用户先刷新 */
    private Map<Long, RecipeDO> loadRecipes(List<RecipeCartItemDO> cartItems) {
        Set<Long> recipeIds = cartItems.stream()
                .map(RecipeCartItemDO::getRecipeId)
                .collect(Collectors.toSet());
        Map<Long, RecipeDO> map = recipeMapper.selectBatchIds(recipeIds).stream()
                .filter(r -> !ContentStatus.DELETED.name().equals(r.getStatus()))
                .collect(Collectors.toMap(RecipeDO::getId, Function.identity()));
        if (map.size() != recipeIds.size()) {
            throw BizException.of(null, "购物车里有菜品已下架，请刷新后重试");
        }
        return map;
    }

    private RecipeOrderDTO toDto(RecipeOrderDO order, List<RecipeOrderItemDO> items) {
        RecipeOrderDTO dto = new RecipeOrderDTO();
        dto.setId(order.getId());
        dto.setStatus(order.getStatus());
        dto.setTotalQty(order.getTotalQty());
        dto.setCreatorId(order.getCreatorId());
        dto.setCreateTime(order.getCreateTime());
        dto.setItems(items.stream().map(item -> {
            RecipeOrderItemDTO itemDto = new RecipeOrderItemDTO();
            itemDto.setRecipeId(item.getRecipeId());
            itemDto.setRecipeName(item.getRecipeName());
            itemDto.setCoverUrl(item.getCoverUrl());
            itemDto.setQty(item.getQty());
            itemDto.setPractices(parsePractices(item.getPractices()));
            return itemDto;
        }).toList());
        return dto;
    }

    /** 做法 JSON 快照 -> 结构化；坏数据（字典变更/历史残留）降级成未选，不影响订单展示 */
    private List<CartPracticeDTO> parsePractices(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CartPracticeDTO>>() {});
        } catch (JsonProcessingException e) {
            log.warn("订单做法 JSON 解析失败，按未选处理: raw={}", json, e);
            return null;
        }
    }
}
