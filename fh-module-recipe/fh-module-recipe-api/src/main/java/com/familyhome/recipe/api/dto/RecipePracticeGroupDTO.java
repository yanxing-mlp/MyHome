package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 菜谱绑定的一个做法分组，以及这道菜在该分组上的两项配置。
 *
 * <p>出入参共用（同 {@link CartPracticeDTO}）：请求里带什么，菜谱详情就返回什么。
 * 组名和组内选项不在这里回，前端按 {@code groupId} 去 /practices 字典关联，与旧口径一致。
 */
@Data
public class RecipePracticeGroupDTO {

    /**
     * 做法分组 ID
     */
    @NotNull(message = "做法分组不能为空")
    private Long groupId;

    /**
     * true = 这道菜必须在该分组里选一个做法；false = 可以不选
     */
    private Boolean required;

    /**
     * 默认选中的选项 ID，NULL = 不预选。不属于本分组时服务端直接报错
     */
    private Long defaultOptionId;
}
