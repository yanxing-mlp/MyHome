package com.familyhome.recipe.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜谱 DTO（方案 §4.3）。
 */
@Data
public class RecipeDTO {

    private Long id;

    /**
     * 菜名
     */
    private String name;

    /**
     * 做法描述
     */
    private String description;

    /**
     * ON_SHELF / OFF_SHELF
     */
    private String status;

    /**
     * 封面图 URL（sort 最小的那张）
     */
    private String coverUrl;

    /**
     * 分类名称（单选）
     */
    private String category;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 可选做法分组（含这道菜的必选/默认选项配置；组名与组内选项由前端从 /practices 字典按 id 关联）
     */
    private List<RecipePracticeGroupDTO> practiceGroups;

    /**
     * 添加人（{@code app_user.id}），昵称由前端从账号字典 {@code user/options} 解析（两端各一条路径）
     */
    private Long creatorId;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
