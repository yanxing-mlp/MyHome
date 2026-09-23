package com.familyhome.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建菜谱请求（方案 §5.6）。
 */
@Data
public class CreateRecipeRequest {

    /**
     * 菜名
     */
    @NotBlank(message = "菜名不能为空")
    private String name;

    /**
     * 做法描述
     */
    private String description;

    /**
     * 状态：ON_SHELF / OFF_SHELF
     */
    private String status;

    /**
     * 分类 ID（单选，新建必填；编辑接口的 UpdateRecipeRequest 保留可不选，兼容存量未分类菜谱）
     */
    @NotNull(message = "菜品分类不能为空")
    private Long categoryId;

    /**
     * 封面图文件 ID 列表（按 sort 排序）
     */
    private List<Long> coverFileIds;

    /**
     * 可选做法分组（辣度/糖等，可空）：每组带这道菜的"是否必选 + 默认选中选项"配置
     */
    @Valid
    private List<RecipePracticeGroupDTO> practiceGroups;
}
