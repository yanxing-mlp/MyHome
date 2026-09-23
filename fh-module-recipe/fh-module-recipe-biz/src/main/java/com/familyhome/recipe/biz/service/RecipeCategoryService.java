package com.familyhome.recipe.biz.service;

import com.familyhome.recipe.api.dto.CreateCategoryRequest;
import com.familyhome.recipe.api.dto.RecipeCategoryDTO;

import java.util.List;

/**
 * 菜谱分类服务。
 */
public interface RecipeCategoryService {

    /**
     * 查询所有分类（按 sortOrder 升序）。
     */
    List<RecipeCategoryDTO> listAll();

    /**
     * 创建分类。
     */
    Long createCategory(CreateCategoryRequest request);

    /**
     * 更新分类。
     */
    void updateCategory(Long id, CreateCategoryRequest request);

    /**
     * 删除分类。
     */
    void deleteCategory(Long id);
}
