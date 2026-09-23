-- 菜谱类型改为菜品分类，并支持排序
-- 一个菜只能关联到一个分类下（多对一）

-- 1. 重命名表（sort_order 字段已在 V302 中添加）
RENAME TABLE `recipe_type` TO `recipe_category`;

-- 2. 更新注释
ALTER TABLE `recipe_category` MODIFY COLUMN `name` VARCHAR(32) NOT NULL COMMENT '分类名';
ALTER TABLE `recipe_category` COMMENT = '菜谱分类字典（硬删除 + 级联解绑）';

-- 3. 删除旧的唯一键约束（按名字）
ALTER TABLE `recipe_category` DROP INDEX `uk_name`;

-- 4. 重新创建唯一键
ALTER TABLE `recipe_category` ADD UNIQUE KEY `uk_name` (`name`);

-- 5. 重命名关联表
RENAME TABLE `recipe_type_rel` TO `recipe_category_rel`;

-- 6. 修改关联表注释和列名
ALTER TABLE `recipe_category_rel` MODIFY COLUMN `type_id` BIGINT UNSIGNED NOT NULL COMMENT '分类 ID';
ALTER TABLE `recipe_category_rel` CHANGE COLUMN `type_id` `category_id` BIGINT UNSIGNED NOT NULL;
ALTER TABLE `recipe_category_rel` COMMENT = '菜谱-分类关联（多对一）';

-- 7. 修改唯一键约束名称
ALTER TABLE `recipe_category_rel` DROP INDEX `uk_recipe_type`;
ALTER TABLE `recipe_category_rel` ADD UNIQUE KEY `uk_recipe_category` (`recipe_id`, `category_id`);

-- 8. 修改索引名称
ALTER TABLE `recipe_category_rel` DROP INDEX `idx_type`;
ALTER TABLE `recipe_category_rel` ADD KEY `idx_category` (`category_id`);
