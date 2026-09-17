/**
 * 菜谱域实现。
 *
 * <p>表前缀 {@code recipe_}，Flyway 目录 {@code db/migration/recipe}（版本号段 V3xx）。
 *
 * <p><b>三态 status 的查询约束</b>：{@code recipe} 用 ON_SHELF/OFF_SHELF/DELETED，
 * {@code @TableLogic} 用不了，每个查询必须显式带 {@code status <> 'DELETED'}（方案 §10 风险 5）。
 * v4 起菜谱删除会一并物理删除图片文件，所以 {@code DELETED} 不可恢复。
 *
 * <p><b>子表策略</b>：{@code recipe_image} / {@code recipe_tag_rel} / {@code recipe_type_rel}
 * 都是物理删除 + 全量覆盖式保存（编辑时按 recipe_id 全删再批量插）。
 * 字典表 {@code recipe_tag} / {@code recipe_type} 是硬删除 + <b>级联解绑</b>
 * （先清关联表再删字典，一个事务内完成）。
 *
 * <p><b>多对多过滤分页必须用 EXISTS 子查询，不能用 JOIN + DISTINCT</b>——
 * JOIN 会让挂了 3 个标签的菜谱出现 3 行，LIMIT 分页错乱、COUNT 也不准（方案 §5.6）。
 * 筛选语义：同维度内 OR，跨维度 AND。
 *
 * <p>跨域依赖：只允许 {@code fh-module-file-api}。
 */
package com.familyhome.recipe.biz;
