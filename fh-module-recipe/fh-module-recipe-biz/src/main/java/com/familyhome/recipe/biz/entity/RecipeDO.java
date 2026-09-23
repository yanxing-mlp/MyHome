package com.familyhome.recipe.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜谱主表（方案 §4.3）。
 */
@Data
@TableName("recipe")
public class RecipeDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 菜名
     */
    private String name;

    /**
     * 做法描述
     */
    private String description;

    /**
     * ON_SHELF / OFF_SHELF / DELETED
     */
    private String status;

    /**
     * 添加人（{@code app_user.id}），V316 加列。
     *
     * <p>只有新建那一行会写它，改名/上下架/换封面都不动，所以"这道菜是谁建的"是稳定的。
     */
    private Long creatorId;

    /**
     * 添加时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
