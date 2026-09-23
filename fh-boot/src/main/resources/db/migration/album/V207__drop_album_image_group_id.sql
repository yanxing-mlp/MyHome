-- album_image.group_id 退役。
--
-- 【为什么现在能删】这张表从一开始就升级成了"一张图一行 + N 条 album_image_group_rel 关联"（V202），
-- 但 group_id 一直留在库里当"主分组"，于是同一个问题有两个答案：
-- 分页查询按关联表筛（EXISTS），而分组张数、封面、级联删除按 group_id 数，
-- 结果一张图可以在 B 端算进「2024」、在 C 端归到「其他」。V206 把历史缺口回填成关联行之后，
-- group_id 已经没有任何一处代码读它了，留着只会再次分叉，所以整列删掉。
--
-- 顺手把 `idx_group_status_time` 一起删了，且<b>不补新的</b>：它的首列就是 group_id，
-- 本来就是"按分组取该组的图按时间排"这条查询的索引；那条路现在由关联表服务
-- （`uk_image_group` / `idx_group` 定位 image_id，再按主键回 album_image），
-- 剩下 (status, create_time) 这个前缀对 `status <> 'DELETED'` 这种不等条件没有可用价值。
--
-- 注：本文件与 V206 必须同批上线 —— 代码侧实体已不再写 group_id，
-- 而这一列原本是 NOT NULL 无默认值，只上代码不上迁移，新增图片会直接撞约束。
ALTER TABLE `album_image`
  DROP INDEX `idx_group_status_time`,
  DROP COLUMN `group_id`;
