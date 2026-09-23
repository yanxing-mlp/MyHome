-- 清洗 album_image_group_rel 的历史孤儿行。
--
-- 背景：删图片原先只软删 `album_image`（status='DELETED'）、删分组原先只置 `album_group.deleted=1`，
-- 两条路都不碰关联表，所以关联表里会留下指向"已删图片 / 已删分组"的行。C 端读图走关联表并按
-- status 过滤，页面上看不出问题，但这张表只会越攒越脏，"某分组关联了多少行"这类数也不可信。
-- 代码侧已改成三条删除路（单张删除 / 批量删除 / 删分组及其级联图片）都硬删关联行，
-- 本迁移只负责把**改代码之前**攒下来的存量孤儿清掉，一次性。
--
-- 为什么外键没帮我们：`fk_rel_image` / `fk_rel_group` 都是 ON DELETE CASCADE，
-- 但图片是软删（UPDATE 不是 DELETE）、分组走 @TableLogic 也是软删，级联压根不会触发。
-- 顺带把两种"理论不该存在"的行一起收掉：图片/分组行已经不在表里了（FK 保证不会，留着当保险）。
--
-- 只关联 `album_image` / `album_group`，不碰城市统计：孤儿行的图片本身就是 DELETED，
-- 不在 `recalculateAllCities()` 的在架口径里，删完 `album_city` 一个数都不会变。

DELETE r
  FROM `album_image_group_rel` r
  LEFT JOIN `album_image` i ON i.`id` = r.`image_id`
  LEFT JOIN `album_group` g ON g.`id` = r.`group_id`
 WHERE i.`id` IS NULL
    OR i.`status` = 'DELETED'
    OR g.`id` IS NULL
    OR g.`deleted` = 1;
