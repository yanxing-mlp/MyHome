-- 补齐 update_time：本仓建表口径要求每张表都有创建时间与更新时间，
-- 关联表（rel）、明细表（order_item）、字典子表（practice_option）都不算例外。
-- 只加列，存量行由 DEFAULT CURRENT_TIMESTAMP(3) 回填为当前时刻。

ALTER TABLE `recipe_category_rel`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `recipe_tag_rel`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `recipe_practice_rel`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `recipe_practice_option`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `recipe_image`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `recipe_order_item`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;
