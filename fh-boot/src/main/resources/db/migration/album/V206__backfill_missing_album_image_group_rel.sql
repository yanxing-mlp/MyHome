-- 回填 album_image_group_rel 缺失的归属行。
--
-- 【为什么会有缺口】关联表 V202 建好时只回填了当时存量的一批（那次 INSERT 之后 group_id 就没再当过查询口径）。
-- 此后凡是走 `album_image.group_id` 插的新图，如果没有同时补上关联行，就会变成"B 端算进某个分组、
-- C 端却归到「其他」"的两套数（实测：图片 6 有 group_id=3、无任何关联行）。
-- 本迁移把"非 DELETED 且有遗留 group_id、但该分组下没有关联行"的图片补齐，一次性；
-- 之后分组归属只有关联表一个答案，`group_id` 那一列在 V207 退役。
--
-- 只回填**未删除**的图片：DELETED 的关联行刚被 V205 当孤儿清掉，给垃圾桶里的图再插回去没有意义。
-- JOIN album_group 是为了不把图挂到已软删的分组上（那种分组 C 端不出卡、B 端列表也查不到）。
INSERT INTO `album_image_group_rel` (`image_id`, `group_id`)
SELECT i.`id`, i.`group_id`
  FROM `album_image` i
  JOIN `album_group` g ON g.`id` = i.`group_id` AND g.`deleted` = 0
 WHERE i.`status` <> 'DELETED'
   AND NOT EXISTS (SELECT 1
                     FROM `album_image_group_rel` r
                    WHERE r.`image_id` = i.`id`
                      AND r.`group_id` = i.`group_id`);
