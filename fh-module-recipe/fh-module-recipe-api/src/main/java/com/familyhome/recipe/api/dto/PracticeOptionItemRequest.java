package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 做法分组编辑请求里的一个选项：带 id=改这个选项的名字，不带 id=新增。
 *
 * <p>id 不属于本分组时按"该选项不存在"处理（服务端会直接报错，不会把别的分组的选项抢过来）。
 */
@Data
public class PracticeOptionItemRequest {

    private Long id;

    @NotBlank(message = "选项名称不能为空")
    private String name;
}
