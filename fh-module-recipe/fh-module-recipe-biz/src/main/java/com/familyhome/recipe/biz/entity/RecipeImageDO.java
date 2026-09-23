package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜谱图片（方案 §4.3）。
 */
@Data
@TableName("recipe_image")
public class RecipeImageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 菜谱 ID
     */
    private Long recipeId;

    /**
     * 文件 ID（关联 fh_file）
     */
    private Long fileId;

    /**
     * 排序，越小越前，sort 最小的是封面
     */
    private Integer sort;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
