package com.familyhome.recipe.api.dto;

import java.util.List;
import lombok.Data;

/**
 * 做法分组 DTO（辣度/糖），内嵌组内选项。
 *
 * <p>分组和选项都不参与排序：按录入顺序（id 升序）返回，C 端默认选中组内第一个。
 */
@Data
public class PracticeGroupDTO {

    private Long id;

    private String name;

    /**
     * 关联菜品数量（已删除的菜品不算）
     */
    private Integer recipeCount;

    private List<PracticeOptionDTO> options;
}
