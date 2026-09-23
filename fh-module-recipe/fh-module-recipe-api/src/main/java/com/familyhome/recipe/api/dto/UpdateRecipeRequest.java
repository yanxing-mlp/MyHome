package com.familyhome.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 更新菜谱请求（方案 §5.6）。
 */
@Data
public class UpdateRecipeRequest {

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
     * 分类 ID（单选）
     */
    private Long categoryId;

    /**
     * 封面图文件 ID 列表（按 sort 排序，覆盖）
     */
    private List<Long> coverFileIds;

    /**
     * 可选做法分组（覆盖；null = 不动，空数组 = 清空）：每组带这道菜的"是否必选 + 默认选中选项"配置
     */
    @Valid
    private List<RecipePracticeGroupDTO> practiceGroups;
}
