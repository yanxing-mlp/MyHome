package com.familyhome.recipe.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.PageResult;
import com.familyhome.file.api.FileFacade;
import com.familyhome.recipe.api.dto.CreateRecipeRequest;
import com.familyhome.recipe.api.dto.RecipeDTO;
import com.familyhome.recipe.api.dto.RecipePracticeGroupDTO;
import com.familyhome.recipe.api.dto.RecipeQueryRequest;
import com.familyhome.recipe.api.dto.UpdateRecipeRequest;
import com.familyhome.recipe.biz.entity.*;
import com.familyhome.recipe.biz.mapper.*;
import com.familyhome.recipe.biz.service.RecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜谱服务实现（方案 §5.6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeServiceImpl implements RecipeService {

    private final RecipeMapper recipeMapper;
    private final RecipeImageMapper imageMapper;
    private final RecipeCategoryMapper categoryMapper;
    private final RecipeCategoryRelMapper categoryRelMapper;
    private final RecipePracticeRelMapper practiceRelMapper;
    private final RecipePracticeOptionMapper practiceOptionMapper;
    private final FileFacade fileFacade;

    @Override
    public PageResult<RecipeDTO> pageRecipes(RecipeQueryRequest request) {
        // 1. 构建主查询条件
        LambdaQueryWrapper<RecipeDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(RecipeDO::getStatus, ContentStatus.DELETED);

        // 关键词搜索
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(RecipeDO::getName, request.getKeyword());
        }

        // 状态筛选
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(RecipeDO::getStatus, request.getStatus());
        }

        // 分类过滤（使用 EXISTS 子查询避免 SQL 注入）
        if (request.getCategoryId() != null) {
            wrapper.inSql(RecipeDO::getId, 
                "SELECT recipe_id FROM recipe_category_rel WHERE category_id = " + request.getCategoryId());
        }

        wrapper.orderByDesc(RecipeDO::getUpdateTime);

        // 2. 分页查询
        Page<RecipeDO> page = new Page<>(request.getPageNo(), request.getPageSize());
        Page<RecipeDO> resultPage = recipeMapper.selectPage(page, wrapper);

        // 3. 批量填充关联数据（避免 N+1）
        List<RecipeDTO> dtos = fillRecipeDetails(resultPage.getRecords());

        return PageResult.of(dtos, resultPage.getTotal(), request.getPageNo(), request.getPageSize());
    }

    @Override
    public List<RecipeDTO> listOnShelf() {
        // 只一个等值条件就把下架与已删除都挡住了，所以这里不再写 ne(DELETED)
        List<RecipeDO> recipes = recipeMapper.selectList(new LambdaQueryWrapper<RecipeDO>()
                .eq(RecipeDO::getStatus, ContentStatus.ON_SHELF.name())
                .orderByDesc(RecipeDO::getUpdateTime));
        return fillRecipeDetails(recipes);
    }

    @Override
    @Transactional
    public Long createRecipe(CreateRecipeRequest request) {
        // 1. 创建菜谱主记录
        RecipeDO recipe = new RecipeDO();
        recipe.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : ContentStatus.ON_SHELF.name());
        recipe.setName(validateName(request.getName(), null, recipe.getStatus()));
        recipe.setDescription(request.getDescription());
        recipe.setCreateTime(LocalDateTime.now());
        recipe.setUpdateTime(LocalDateTime.now());
        recipe.setCreatorId(CurrentUserHolder.requireUserId());
        recipeMapper.insert(recipe);

        // 2. 关联分类（单选）
        if (request.getCategoryId() != null) {
            RecipeCategoryRelDO rel = new RecipeCategoryRelDO();
            rel.setRecipeId(recipe.getId());
            rel.setCategoryId(request.getCategoryId());
            categoryRelMapper.insert(rel);
        }

        // 3. 关联做法分组（可多选，可空；每组带必选/默认选项配置）
        savePracticeRels(recipe.getId(), request.getPracticeGroups());

        // 4. 关联封面图
        if (!CollectionUtils.isEmpty(request.getCoverFileIds())) {
            for (int i = 0; i < request.getCoverFileIds().size(); i++) {
                RecipeImageDO image = new RecipeImageDO();
                image.setRecipeId(recipe.getId());
                image.setFileId(request.getCoverFileIds().get(i));
                image.setSort(i);
                imageMapper.insert(image);
            }
        }

        log.info("新增菜谱: id={}, name={}, categoryId={}, practices={}, status={}",
                recipe.getId(), recipe.getName(), request.getCategoryId(),
                request.getPracticeGroups(), recipe.getStatus());
        return recipe.getId();
    }

    @Override
    @Transactional
    public void updateRecipe(Long id, UpdateRecipeRequest request) {
        // 当前读串行化同一菜品的编辑/删除，不能把并发删除前的旧 status 写回并重新占名。
        RecipeDO recipe = requireRecipeForUpdate(id);

        // 2. 更新主记录
        if (StringUtils.hasText(request.getStatus())) {
            recipe.setStatus(request.getStatus());
        }
        recipe.setName(validateName(request.getName(), id, recipe.getStatus()));
        recipe.setDescription(request.getDescription());
        recipe.setUpdateTime(LocalDateTime.now());
        recipeMapper.updateById(recipe);

        // 3. 覆盖分类关联（单选）
        if (request.getCategoryId() != null) {
            categoryRelMapper.delete(new LambdaQueryWrapper<RecipeCategoryRelDO>()
                    .eq(RecipeCategoryRelDO::getRecipeId, id));
            RecipeCategoryRelDO rel = new RecipeCategoryRelDO();
            rel.setRecipeId(id);
            rel.setCategoryId(request.getCategoryId());
            categoryRelMapper.insert(rel);
        }

        // 4. 覆盖做法分组关联（null = 不动，空数组 = 清空）
        savePracticeRels(id, request.getPracticeGroups());

        // 5. 覆盖封面图关联
        if (request.getCoverFileIds() != null) {
            imageMapper.delete(new LambdaQueryWrapper<RecipeImageDO>()
                    .eq(RecipeImageDO::getRecipeId, id));
            if (!request.getCoverFileIds().isEmpty()) {
                for (int i = 0; i < request.getCoverFileIds().size(); i++) {
                    RecipeImageDO image = new RecipeImageDO();
                    image.setRecipeId(id);
                    image.setFileId(request.getCoverFileIds().get(i));
                    image.setSort(i);
                    imageMapper.insert(image);
                }
            }
        }

        log.info("更新菜谱: id={}, name={}, categoryId={}, practices={}, status={}",
                id, recipe.getName(), request.getCategoryId(),
                request.getPracticeGroups(), recipe.getStatus());
    }

    @Override
    @Transactional
    public void deleteRecipe(Long id) {
        RecipeDO recipe = requireRecipeForUpdate(id);

        // 软删：只改状态
        recipe.setStatus(ContentStatus.DELETED.name());
        recipe.setUpdateTime(LocalDateTime.now());
        recipeMapper.updateById(recipe);

        log.info("删除菜谱: id={}, name={}", id, recipe.getName());
    }

    @Override
    public RecipeDTO getRecipeDetail(Long id) {
        RecipeDO recipe = recipeMapper.selectById(id);
        if (recipe == null || ContentStatus.DELETED.name().equals(recipe.getStatus())) {
            throw BizException.notFound(null, "菜谱不存在");
        }

        // 填充详情
        List<RecipeDTO> dtos = fillRecipeDetails(List.of(recipe));
        return dtos.isEmpty() ? null : dtos.get(0);
    }

    private RecipeDO requireRecipeForUpdate(Long id) {
        RecipeDO recipe = recipeMapper.selectOne(new LambdaQueryWrapper<RecipeDO>()
                .eq(RecipeDO::getId, id).last("FOR UPDATE"));
        if (recipe == null || ContentStatus.DELETED.name().equals(recipe.getStatus())) {
            throw BizException.notFound(null, "菜谱不存在");
        }
        return recipe;
    }

    private String validateName(String name, Long excludeId, String status) {
        name = name == null ? "" : name.trim();
        if (!StringUtils.hasText(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "菜名不能为空");
        }
        // 只有已删除的菜谱释放名称，下架仍占名；比较交给数据库，兼容存量首尾空格。
        if (!ContentStatus.DELETED.name().equals(status)
                && recipeMapper.selectCount(new LambdaQueryWrapper<RecipeDO>()
                        .apply("TRIM(name) = {0}", name.trim())
                        .ne(RecipeDO::getStatus, ContentStatus.DELETED.name())
                        .ne(excludeId != null, RecipeDO::getId, excludeId)) > 0) {
            throw BizException.of(ErrorCode.RECIPE_NAME_DUPLICATED, "菜谱名称已存在");
        }
        return name;
    }

    /**
     * 批量填充菜谱详情（分类、做法、封面图）。
     */
    private List<RecipeDTO> fillRecipeDetails(List<RecipeDO> recipes) {
        if (recipes.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> recipeIds = recipes.stream().map(RecipeDO::getId).toList();

        // 批量查询分类关联（单选）
        Map<Long, String> categoryNameMap = batchQueryCategorySingle(recipeIds);
        Map<Long, Long> categoryIdMap = batchQueryCategoryId(recipeIds);

        // 批量查询封面图（sort 最小的那张）
        Map<Long, String> coverMap = coverUrls(recipeIds);

        // 批量查询做法分组关联（多选，带这道菜的必选/默认配置）
        Map<Long, List<RecipePracticeGroupDTO>> practiceGroupMap = batchQueryPracticeGroups(recipeIds);

        // 组装 DTO
        return recipes.stream().map(recipe -> {
            RecipeDTO dto = new RecipeDTO();
            dto.setId(recipe.getId());
            dto.setName(recipe.getName());
            dto.setDescription(recipe.getDescription());
            dto.setStatus(recipe.getStatus());
            dto.setUpdateTime(recipe.getUpdateTime());
            dto.setCategory(categoryNameMap.get(recipe.getId()));
            dto.setCategoryId(categoryIdMap.get(recipe.getId()));
            dto.setPracticeGroups(practiceGroupMap.getOrDefault(recipe.getId(), Collections.emptyList()));
            dto.setCreatorId(recipe.getCreatorId());
            dto.setCoverUrl(coverMap.get(recipe.getId()));
            return dto;
        }).toList();
    }

    /**
     * 批量查询分类（单选）。
     */
    private Map<Long, String> batchQueryCategorySingle(List<Long> recipeIds) {
        List<RecipeCategoryRelDO> rels = categoryRelMapper.selectList(
                new LambdaQueryWrapper<RecipeCategoryRelDO>()
                        .in(RecipeCategoryRelDO::getRecipeId, recipeIds)
        );

        if (rels.isEmpty()) {
            return Collections.emptyMap();
        }

        // 每个菜谱只关联一个分类，取第一个
        Set<Long> categoryIds = rels.stream().map(RecipeCategoryRelDO::getCategoryId).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(RecipeCategoryDO::getId, RecipeCategoryDO::getName));

        return rels.stream()
                .filter(rel -> categoryNameMap.containsKey(rel.getCategoryId()))
                .collect(Collectors.toMap(
                        RecipeCategoryRelDO::getRecipeId,
                        rel -> categoryNameMap.get(rel.getCategoryId()),
                        (existing, replacement) -> existing // 如果有重复，保留第一个
                ));
    }

    /**
     * 批量查询分类 ID（单选）。
     */
    private Map<Long, Long> batchQueryCategoryId(List<Long> recipeIds) {
        List<RecipeCategoryRelDO> rels = categoryRelMapper.selectList(
                new LambdaQueryWrapper<RecipeCategoryRelDO>()
                        .in(RecipeCategoryRelDO::getRecipeId, recipeIds)
        );

        if (rels.isEmpty()) {
            return Collections.emptyMap();
        }

        return rels.stream()
                .collect(Collectors.toMap(
                        RecipeCategoryRelDO::getRecipeId,
                        RecipeCategoryRelDO::getCategoryId,
                        (existing, replacement) -> existing // 如果有重复，保留第一个
                ));
    }

    /**
     * 覆盖做法分组关联：null = 不动，空数组 = 清空（口径同分类/封面图）。
     *
     * <p>整删整插没问题：购物车和订单快照里存的是 optionId，不是关联行的 id，
     * 重建关联行不会让用户已选的做法查不到。
     */
    private void savePracticeRels(Long recipeId, List<RecipePracticeGroupDTO> practiceGroups) {
        if (practiceGroups == null) {
            return;
        }
        practiceRelMapper.delete(new LambdaQueryWrapper<RecipePracticeRelDO>()
                .eq(RecipePracticeRelDO::getRecipeId, recipeId));
        for (RecipePracticeGroupDTO binding : practiceGroups) {
            validateDefaultOption(binding);
            RecipePracticeRelDO rel = new RecipePracticeRelDO();
            rel.setRecipeId(recipeId);
            rel.setGroupId(binding.getGroupId());
            rel.setRequired(Boolean.TRUE.equals(binding.getRequired()));
            rel.setDefaultOptionId(binding.getDefaultOptionId());
            practiceRelMapper.insert(rel);
        }
    }

    /**
     * 默认选中的选项必须真的是这个分组下的选项：脏 id 会让 C 端预选到一个查不到的选项上，
     * 展示成"谁都没选"，而必选组又会让用户下不了单。绑定的分组数量有限，逐个查就够。
     */
    private void validateDefaultOption(RecipePracticeGroupDTO binding) {
        Long optionId = binding.getDefaultOptionId();
        if (optionId == null) {
            return;
        }
        RecipePracticeOptionDO option = practiceOptionMapper.selectById(optionId);
        if (option == null || !option.getGroupId().equals(binding.getGroupId())) {
            throw BizException.of(null, "默认选中的做法选项不存在");
        }
    }

    /**
     * 批量查询做法分组关联（多选，按分组 id 升序）。
     */
    private Map<Long, List<RecipePracticeGroupDTO>> batchQueryPracticeGroups(List<Long> recipeIds) {
        List<RecipePracticeRelDO> rels = practiceRelMapper.selectList(
                new LambdaQueryWrapper<RecipePracticeRelDO>()
                        .in(RecipePracticeRelDO::getRecipeId, recipeIds)
                        .orderByAsc(RecipePracticeRelDO::getGroupId)
        );

        if (rels.isEmpty()) {
            return Collections.emptyMap();
        }

        return rels.stream()
                .collect(Collectors.groupingBy(
                        RecipePracticeRelDO::getRecipeId,
                        Collectors.mapping(rel -> {
                            RecipePracticeGroupDTO dto = new RecipePracticeGroupDTO();
                            dto.setGroupId(rel.getGroupId());
                            dto.setRequired(rel.getRequired());
                            dto.setDefaultOptionId(rel.getDefaultOptionId());
                            return dto;
                        }, Collectors.toList())
                ));
    }

    /**
     * 批量查询封面图（每个菜谱 sort 最小的那张）。
     */
    @Override
    public Map<Long, String> coverUrls(List<Long> recipeIds) {
        if (recipeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 查询每个菜谱的第一张图片（sort 最小）
        List<RecipeImageDO> images = imageMapper.selectList(
                new LambdaQueryWrapper<RecipeImageDO>()
                        .in(RecipeImageDO::getRecipeId, recipeIds)
                        .orderByAsc(RecipeImageDO::getSort)
        );

        if (images.isEmpty()) {
            return Collections.emptyMap();
        }

        // 按 recipeId 分组，取每组第一张的 fileId
        Map<Long, Long> recipeCoverFileMap = images.stream()
                .collect(Collectors.toMap(
                        RecipeImageDO::getRecipeId,
                        RecipeImageDO::getFileId,
                        (existing, replacement) -> existing // 保留第一个（sort 最小）
                ));

        if (recipeCoverFileMap.isEmpty()) {
            return Collections.emptyMap();
        }

        // 批量获取文件 DTO
        Set<Long> fileIds = recipeCoverFileMap.values().stream().collect(Collectors.toSet());
        Map<Long, com.familyhome.file.api.dto.FileDTO> fileDtoMap = fileFacade.mapByIds(new ArrayList<>(fileIds));

        // 文件记录缺失（脏数据）就不进结果：当"这道菜没图"处理，不能让整页/整单查不出来
        Map<Long, String> covers = new HashMap<>();
        recipeCoverFileMap.forEach((recipeId, fileId) -> {
            com.familyhome.file.api.dto.FileDTO dto = fileDtoMap.get(fileId);
            if (dto != null) {
                covers.put(recipeId, dto.getUrl());
            }
        });
        return covers;
    }
}
