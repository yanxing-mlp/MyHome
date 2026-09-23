package com.familyhome.recipe.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

/**
 * 更新做法分组请求：组名 + 组内选项一次提交（编辑弹窗点"确定"就发这一个请求）。
 */
@Data
public class UpdatePracticeGroupRequest {

    @NotBlank(message = "分组名称不能为空")
    private String name;

    /**
     * 组内选项，整体覆盖（口径同菜谱的做法分组关联）：null=不动，空数组=清空。
     *
     * <p>服务端按差量处理——带 id 的改名字，不带 id 的新增，这次没列出的删掉。
     * 不做成"全删重插"是为了保住选项 id：购物车和订单快照里的做法 JSON 存的就是 optionId，
     * id 一变，用户已经选好的做法就查不到了。
     */
    @Valid
    private List<PracticeOptionItemRequest> options;
}
