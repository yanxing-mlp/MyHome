package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 做法选项（组内一项：不辣/微辣/好辣）。组内按 id（即录入顺序）展示，
 * C 端进详情时预选哪个由菜谱-分组关联行上的 {@code default_option_id} 决定。
 */
@Data
@TableName("recipe_practice_option")
public class RecipePracticeOptionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 选项名
     */
    private String name;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
