-- 为菜谱标签和类型字典添加 sort_order 字段（用于排序）

ALTER TABLE `recipe_tag` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序权重' AFTER `name`;
ALTER TABLE `recipe_type` ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序权重' AFTER `name`;
