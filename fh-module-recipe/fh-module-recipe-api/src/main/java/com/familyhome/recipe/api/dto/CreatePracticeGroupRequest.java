package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建做法分组请求。
 *
 * <p>只建分组：选项在编辑弹窗里一次填（见 {@link UpdatePracticeGroupRequest}）。
 */
@Data
public class CreatePracticeGroupRequest {

    @NotBlank(message = "分组名称不能为空")
    private String name;
}
