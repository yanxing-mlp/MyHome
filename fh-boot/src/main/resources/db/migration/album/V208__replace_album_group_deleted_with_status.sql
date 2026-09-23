-- album_group 从两态 `deleted` 换成三态 `status`（V2xx 号段续）。
--
-- 【为什么要动存储方式】分组要加"下架"这一档：整本相册对 C 端不再展示，但 B 端照旧看得见、管得了。
-- `deleted TINYINT` 表达不了它——只有 0/1，1 的含义是"这行没了"，而"下架"要求行还在、B 端还在列。
-- 如果保留 `deleted` 再并排加一个 `status`，一行上就叠了两套删除机制：@TableLogic 按 deleted 过滤、
-- 业务条件按 status 过滤，漏配一边就会把已删分组当成下架分组列出来。所以整列换掉，
-- 与 `album_image` / `recipe` 同构，全库少一种删除过滤机制。
--
-- 【回填】DELETED 只能从 `deleted = 1` 搬过来；`deleted = 0` 的行落到 DEFAULT 'ON_SHELF'，不用写。
--
-- 【索引不补】原来的 `idx_deleted_sort(deleted, sort)` 直接删掉，也不留 `(status, sort)` 的替身：
-- 分组查询的过滤条件永远是 `status <> 'DELETED'`（不等值，用不上索引前缀），排序还同时带 create_time；
-- 这张表在家庭场景就几十行，全表扫比维护一条用不上的索引便宜。与 V207 删 `idx_group_status_time`
-- 不补新索引是同一个判断。
--
-- 注：本文件必须与代码同批上线——实体已去掉 `@TableLogic deleted`，只上代码不上迁移，
-- 所有分组查询会因为没有 status 列直接报错。

ALTER TABLE `album_group`
  ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF/DELETED' AFTER `sort`;

UPDATE `album_group` SET `status` = 'DELETED' WHERE `deleted` = 1;

ALTER TABLE `album_group`
  DROP INDEX `idx_deleted_sort`,
  DROP COLUMN `deleted`,
  COMMENT = '相册分组（三态状态）';
