-- 清掉"没有任何业务对象引用"的存量 file_object 行（标删，物理文件见文末说明）。
--
-- 【这些行怎么来的】改代码之前那几批：
--   1) 只上传、从没绑定成功的（B 端/C 端上传走到第 3 步就挂了或用户反悔，文件躺在库里、album_image 一行没有）；
--   2) 老删除路径留下的（`AlbumImageService.delete` 原先不调 markDeletedAndPurge，图片行进 DELETED 了、文件行还是 deleted=0）。
-- 现在三条删除路都会连着清文件，绑定又是必选（不选分组前端就置灰），所以新增不会产生这类行 —— 本迁移只管存量。
--
-- 【口径】"没人引用"= 未删除的 `album_image` 没有一张用它、`recipe_image` 没有一行用它（菜谱图片是硬删 +
-- 全量覆盖，有行就是活的），并且不是文档行（`biz_type = 'document'` 的文档没有外层业务表，
-- file_object 这一行本身就是答案，删它只能走文档管理页）。
-- 再加一条 `recipe_order_item.cover_url` 快照保护：订单明细存的是下单那一刻的 URL 字符串，
-- 物理文件没了历史订单卡上的图就 404，所以哪怕业务对象已经不引用它、只要还有单子引用着就不清。
-- 实测本库里被这条件挡住的是 file_object id 9（南昌拌粉的封面，同时也是一张订单快照）。
--
-- 【命中的行】id 1/2（早期 28 字节的 test.jpg 秒传对）、id 5/6/7（同一次 315KB 截图传了三遍，
-- 秒传硬链接到同一个 inode，nlink=3）、id 8（test_recipe.jpg），合计不到 1MB。
-- id 1 那行的物理文件在磁盘上早就不在了（先被删过一次路径、行没标），正好一起标掉。
--
-- 【物理文件】迁移只能改库，删不了磁盘。本迁移跑完后按 `fh.storage.root` 手动 unlink 一次
-- （主图 + `_t` 缩略图，路径 = {root}/{file_key}）：硬链接只是多一个目录项，unlink 本行这条路径
-- 不会影响别的行还挂在上面的那份；等这三行都 unlink 完，那份数据才真正释放。
-- 老规矩同 album/V205：这类"改代码之前的存量"用一条一次性清洗解决，之后靠代码口径不再产生。
UPDATE `file_object` f
   SET f.`deleted` = 1
 WHERE f.`deleted` = 0
   AND f.`biz_type` <> 'document'
   AND NOT EXISTS (SELECT 1
                     FROM `album_image` i
                    WHERE i.`file_id` = f.`id`
                      AND i.`status` <> 'DELETED')
   AND NOT EXISTS (SELECT 1
                     FROM `recipe_image` ri
                    WHERE ri.`file_id` = f.`id`)
   AND NOT EXISTS (SELECT 1
                     FROM `recipe_order_item` oi
                    WHERE oi.`cover_url` LIKE CONCAT('%', SUBSTRING_INDEX(f.`file_key`, '/', -1), '%'));
