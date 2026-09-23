-- 补齐 update_time：本仓建表口径要求每张表都有创建时间与更新时间，
-- 关联表/明细表也不例外（此前只有 create_time，无法回答"这条绑定后来动过没有"）。
-- 只加列，存量行由 DEFAULT CURRENT_TIMESTAMP(3) 回填为当前时刻。

ALTER TABLE `album_image_group_rel`
  ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;
