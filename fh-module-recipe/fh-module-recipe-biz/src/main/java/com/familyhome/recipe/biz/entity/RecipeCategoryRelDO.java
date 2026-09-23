package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜谱-分类关联（多对一，方案 §4.3）。
 */
@Data
@TableName("recipe_category_rel")
public class RecipeCategoryRelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 菜谱 ID
     */
    private Long recipeId;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
