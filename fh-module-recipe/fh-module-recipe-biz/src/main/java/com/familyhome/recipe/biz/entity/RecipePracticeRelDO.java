package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜谱-做法分组关联（多对多，新建菜品时勾选）。
 *
 * <p>一行除了"绑了哪个分组"，还带这道菜在该分组上的两项配置：是否必选、默认选中哪个选项。
 */
@Data
@TableName("recipe_practice_rel")
public class RecipePracticeRelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recipeId;

    private Long groupId;

    /**
     * true = 这道菜必须在该分组里选一个做法；false = 可以不选
     */
    private Boolean required;

    /**
     * 默认选中的选项 ID，NULL = 不预选。选项日后被删掉时不清空本列，读侧按"无默认"处理。
     */
    private Long defaultOptionId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
