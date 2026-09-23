package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 做法分组字典（辣度/糖）。硬删除 + 级联删选项和解绑。
 *
 * <p>没有排序字段：列表和 C 端都按 id（即录入顺序）展示。
 */
@Data
@TableName("recipe_practice_group")
public class RecipePracticeGroupDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分组名
     */
    private String name;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
