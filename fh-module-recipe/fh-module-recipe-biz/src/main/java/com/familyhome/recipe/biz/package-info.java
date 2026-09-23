/**
 * 菜谱域实现。
 *
 * <p>表前缀 {@code recipe_}，Flyway 目录 {@code db/migration/recipe}（版本号段 V3xx）。
 *
 * <p><b>三态 status 的查询约束</b>：{@code recipe} 用 ON_SHELF/OFF_SHELF/DELETED，
 * {@code @TableLogic} 用不了，每个查询必须显式带 {@code status <> 'DELETED'}（方案 §10 风险 5）。
 * v4 起菜谱删除会一并物理删除图片文件，所以 {@code DELETED} 不可恢复。
 *
 * <p><b>子表策略</b>：{@code recipe_image} / {@code recipe_category_rel} / {@code recipe_practice_rel}
 * 都是物理删除 + 全量覆盖式保存（编辑时按 recipe_id 全删再批量插）。
 * 字典表 {@code recipe_category} / {@code recipe_practice_group} 是硬删除 + <b>级联解绑</b>
 * （先清关联表再删字典，一个事务内完成）。
 *
 * <p><b>挂关联表的过滤分页必须用 EXISTS 子查询，不能用 JOIN + DISTINCT</b>——
 * JOIN 会让命中多条关联的菜谱重复出现，LIMIT 分页错乱、COUNT 也不准（方案 §5.6）。
 *
 * <p><b>菜品标签（{@code recipe_tag}）这个概念已整体删除</b>（V314）："这道菜算哪一组"归分类、
 * "这道菜怎么做"归做法，V313 删掉 C 端"餐段"筛菜之后标签已经没有任何消费方，留着只是多一个能填可不填的字段。
 *
 * <p>跨域依赖：只允许 {@code fh-module-file-api}。
 */
package com.familyhome.recipe.biz;
