package com.familyhome.recipe.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.recipe.api.dto.CreateCategoryRequest;
import com.familyhome.recipe.api.dto.RecipeCategoryDTO;
import com.familyhome.recipe.biz.entity.RecipeCategoryDO;
import com.familyhome.recipe.biz.entity.RecipeCategoryRelDO;
import com.familyhome.recipe.biz.entity.RecipeDO;
import com.familyhome.recipe.biz.mapper.RecipeCategoryMapper;
import com.familyhome.recipe.biz.mapper.RecipeCategoryRelMapper;
import com.familyhome.recipe.biz.mapper.RecipeMapper;
import com.familyhome.recipe.biz.service.RecipeCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜谱分类服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeCategoryServiceImpl implements RecipeCategoryService {

    private final RecipeCategoryMapper categoryMapper;
    private final RecipeCategoryRelMapper categoryRelMapper;
    private final RecipeMapper recipeMapper;

    @Override
    public List<RecipeCategoryDTO> listAll() {
        LambdaQueryWrapper<RecipeCategoryDO> wrapper = new LambdaQueryWrapper<>();
        // 按排序权重由低到高；权重相同（如历史数据都是 0）时按 id 兜底，保证顺序稳定
        wrapper.orderByAsc(RecipeCategoryDO::getSortOrder).orderByAsc(RecipeCategoryDO::getId);
        List<RecipeCategoryDO> categories = categoryMapper.selectList(wrapper);
        
        // 统计每个分类关联的菜品数量（只算未删除的菜，口径同做法管理那一列）
        Map<Long, Long> recipeCountMap = countLiveRecipesByCategory();
        
        return categories.stream().map(category -> toDTO(category, recipeCountMap.getOrDefault(category.getId(), 0L))).toList();
    }

    /**
     * 每个分类关联的菜品数量。
     *
     * <p>删菜是软删（只把 recipe.status 改成 DELETED，分类关联行留着），直接数关联行会把删掉的菜
     * 也算进去，比菜谱列表页的条数还多。下架的菜照样算——菜还在，只是暂时不卖。
     */
    private Map<Long, Long> countLiveRecipesByCategory() {
        List<Long> liveRecipeIds = recipeMapper.selectList(new LambdaQueryWrapper<RecipeDO>()
                        .select(RecipeDO::getId)
                        .ne(RecipeDO::getStatus, ContentStatus.DELETED.name()))
                .stream()
                .map(RecipeDO::getId)
                .toList();
        if (liveRecipeIds.isEmpty()) {
            return Map.of();
        }
        return categoryRelMapper.selectList(new LambdaQueryWrapper<RecipeCategoryRelDO>()
                        .in(RecipeCategoryRelDO::getRecipeId, liveRecipeIds))
                .stream()
                .collect(Collectors.groupingBy(RecipeCategoryRelDO::getCategoryId, Collectors.counting()));
    }

    @Override
    @Transactional
    public Long createCategory(CreateCategoryRequest request) {
        RecipeCategoryDO category = new RecipeCategoryDO();
        category.setName(validateName(request.getName(), null));
        category.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        category.setCreatorId(CurrentUserHolder.requireUserId());
        categoryMapper.insert(category);
        log.info("新增菜谱分类: id={}, name={}, sortOrder={}",
                category.getId(), category.getName(), category.getSortOrder());
        return category.getId();
    }

    @Override
    @Transactional
    public void updateCategory(Long id, CreateCategoryRequest request) {
        RecipeCategoryDO category = categoryMapper.selectById(id);
        if (category == null) {
            throw BizException.notFound(null, "分类不存在");
        }
        category.setName(validateName(request.getName(), id));
        category.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : category.getSortOrder());
        categoryMapper.updateById(category);
        log.info("更新菜谱分类: id={}, name={}, sortOrder={}", id, category.getName(), category.getSortOrder());
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        RecipeCategoryDO category = categoryMapper.selectById(id);
        if (category == null) {
            throw BizException.notFound(null, "分类不存在");
        }
        // 先解绑菜品再删字典：留着关联行就是孤儿数据，那些菜在 C 端会直接从菜单上消失
        int rels = categoryRelMapper.delete(new LambdaQueryWrapper<RecipeCategoryRelDO>()
                .eq(RecipeCategoryRelDO::getCategoryId, id));
        categoryMapper.deleteById(id);
        log.info("删除菜谱分类: id={}, name={}, 解绑菜品={}", id, category.getName(), rels);
    }

    private String validateName(String name, Long excludeId) {
        name = name == null ? "" : name.trim();
        if (!StringUtils.hasText(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "分类名称不能为空");
        }
        if (categoryMapper.selectCount(new LambdaQueryWrapper<RecipeCategoryDO>()
                .apply("TRIM(name) = {0}", name.trim())
                .ne(excludeId != null, RecipeCategoryDO::getId, excludeId)) > 0) {
            throw BizException.of(ErrorCode.RECIPE_CATEGORY_NAME_DUPLICATED, "菜谱分类名称已存在");
        }
        return name;
    }

    private RecipeCategoryDTO toDTO(RecipeCategoryDO category, long recipeCount) {
        RecipeCategoryDTO dto = new RecipeCategoryDTO();
        dto.setId(category.getId());
        dto.setName(category.getName());
        dto.setSortOrder(category.getSortOrder());
        dto.setRecipeCount((int) recipeCount);
        dto.setCreatorId(category.getCreatorId());
        return dto;
    }
}
