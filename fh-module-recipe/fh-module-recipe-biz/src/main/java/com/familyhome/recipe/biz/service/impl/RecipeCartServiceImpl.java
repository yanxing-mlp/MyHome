package com.familyhome.recipe.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.recipe.api.dto.CartItemDTO;
import com.familyhome.recipe.api.dto.CartItemRequest;
import com.familyhome.recipe.api.dto.CartPracticeDTO;
import com.familyhome.recipe.api.dto.CartSnapshotDTO;
import com.familyhome.recipe.biz.entity.RecipeCartItemDO;
import com.familyhome.recipe.biz.entity.RecipeDO;
import com.familyhome.recipe.biz.mapper.RecipeCartItemMapper;
import com.familyhome.recipe.biz.mapper.RecipeMapper;
import com.familyhome.recipe.biz.service.RecipeCartService;
import com.familyhome.recipe.biz.service.RecipeCartState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 点餐购物车服务实现。
 *
 * <p>一菜一行不变；所选做法以 JSON 串存在 {@code practices} 列上，
 * {@code setItem} 整体覆盖（请求不带 practices 即视为清空做法）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeCartServiceImpl implements RecipeCartService {

    private final RecipeCartItemMapper cartMapper;
    private final RecipeMapper recipeMapper;
    private final RecipeCartState cartState;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CartSnapshotDTO listCart() {
        Long version = cartState.lock();
        return snapshot(version);
    }

    /** 仅在持有车锁的事务中组装，不能把版本与明细拆成两次请求读取。 */
    private CartSnapshotDTO snapshot(Long version) {
        List<RecipeCartItemDO> items = cartMapper.selectList(
                new LambdaQueryWrapper<RecipeCartItemDO>().orderByAsc(RecipeCartItemDO::getId));
        CartSnapshotDTO snapshot = new CartSnapshotDTO();
        snapshot.setVersion(version);
        snapshot.setItems(items.stream().map(item -> {
            CartItemDTO dto = new CartItemDTO();
            dto.setRecipeId(item.getRecipeId());
            dto.setQty(item.getQty());
            dto.setPractices(parsePractices(item.getPractices()));
            return dto;
        }).toList());
        return snapshot;
    }

    @Override
    @Transactional
    public CartSnapshotDTO setItem(CartItemRequest request) {
        Long version = cartState.lock();
        cartState.checkVersion(request.getVersion(), version);
        if (request.getQty() <= 0) {
            removeItem(request.getRecipeId());
            return snapshot(cartState.advance(version));
        }
        RecipeDO recipe = recipeMapper.selectById(request.getRecipeId());
        if (recipe == null || ContentStatus.DELETED.name().equals(recipe.getStatus())) {
            throw BizException.notFound(null, "菜品不存在");
        }
        String practicesJson = writePractices(request.getPractices());
        log.info("购物车改量: recipeId={}, qty={}, practices={}",
                request.getRecipeId(), request.getQty(), practicesJson);
        RecipeCartItemDO existing = cartMapper.selectOne(
                new LambdaQueryWrapper<RecipeCartItemDO>()
                        .eq(RecipeCartItemDO::getRecipeId, request.getRecipeId()));
        if (existing == null) {
            RecipeCartItemDO item = new RecipeCartItemDO();
            item.setRecipeId(request.getRecipeId());
            item.setQty(request.getQty());
            item.setPractices(practicesJson);
            cartMapper.insert(item);
        } else {
            // 用 UpdateWrapper 显式 SET：updateById 的 NOT_NULL 策略会跳过 null，
            // 导致"不带 practices = 清空做法"覆盖不掉旧值
            cartMapper.update(null, new LambdaUpdateWrapper<RecipeCartItemDO>()
                    .eq(RecipeCartItemDO::getId, existing.getId())
                    .set(RecipeCartItemDO::getQty, request.getQty())
                    .set(RecipeCartItemDO::getPractices, practicesJson));
        }
        return snapshot(cartState.advance(version));
    }

    @Override
    @Transactional
    public CartSnapshotDTO clearCart(Long version) {
        Long currentVersion = cartState.lock();
        cartState.checkVersion(version, currentVersion);
        int removed = cartMapper.delete(new LambdaQueryWrapper<>());
        log.info("清空购物车: removed={}", removed);
        return snapshot(cartState.advance(currentVersion));
    }

    private void removeItem(Long recipeId) {
        int rows = cartMapper.delete(new LambdaQueryWrapper<RecipeCartItemDO>()
                .eq(RecipeCartItemDO::getRecipeId, recipeId));
        if (rows > 0) {
            log.info("购物车移除: recipeId={}", recipeId);
        }
    }

    /** 所选做法 → JSON 串；空 = NULL 覆盖（表示无做法）。 */
    private String writePractices(List<CartPracticeDTO> practices) {
        if (CollectionUtils.isEmpty(practices)) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(practices);
        } catch (JsonProcessingException e) {
            throw BizException.of(null, "做法数据格式错误");
        }
    }

    /** JSON 串 → 所选做法；坏数据（历史/字典变更后残留）降级为 null，不影响购物车展示。 */
    private List<CartPracticeDTO> parsePractices(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CartPracticeDTO>>() {});
        } catch (JsonProcessingException e) {
            log.warn("购物车做法 JSON 解析失败，按未选处理: raw={}", json, e);
            return null;
        }
    }
}
