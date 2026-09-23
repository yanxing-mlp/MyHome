package com.familyhome.recipe.biz.service;

import com.familyhome.recipe.api.dto.CreatePracticeGroupRequest;
import com.familyhome.recipe.api.dto.PracticeGroupDTO;
import com.familyhome.recipe.api.dto.UpdatePracticeGroupRequest;

import java.util.List;

/**
 * 做法字典服务（分组 + 组内选项）。
 *
 * <p>只有分组这一层接口：选项跟着分组一起存（编辑弹窗一次提交组名和全部选项），
 * 所以不提供单个选项的增改删接口。
 */
public interface RecipePracticeService {

    /**
     * 查询全部分组（含组内选项与关联菜品数量，均按录入顺序返回，不参与排序）。
     */
    List<PracticeGroupDTO> listAll();

    Long createGroup(CreatePracticeGroupRequest request);

    /**
     * 更新分组：改组名 + 按差量覆盖组内选项。
     */
    void updateGroup(Long id, UpdatePracticeGroupRequest request);

    /**
     * 删除分组（级联删组内选项 + 解绑菜品关联）。
     */
    void deleteGroup(Long id);
}
