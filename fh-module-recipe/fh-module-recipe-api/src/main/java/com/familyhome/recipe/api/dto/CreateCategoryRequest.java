package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建分类请求。
 */
@Data
public class CreateCategoryRequest {

    @NotBlank(message = "分类名称不能为空")
    private String name;

    private Integer sortOrder;
}
