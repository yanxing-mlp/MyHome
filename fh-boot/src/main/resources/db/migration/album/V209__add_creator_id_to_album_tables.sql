-- album 域（V2xx 号段续）：相册分组与相册图片各加"添加人"。
--
-- 口径与 file/V104 那条一致（可空、不建索引、只存 id 不存昵称，昵称前端查账号字典），
-- 这里只记相册域特有的两点。
--
-- 【album_image 的"添加人"是上传者，不是把它挂进分组的人】
-- album_image 是"一图一行"的复用表：同一张图（按 md5 秒传/复用已有行）绑到第二个分组时
-- 走的是"查到已有行就不再 INSERT"那条路（AlbumGroupService 的绑定流水），所以 creator_id
-- 只在**第一次建这一行**那一刻写，之后挂到几个分组都不动它。
-- 也就是说这一列回答的是"这张照片谁传上来的"，而"谁把它放进这本相册"是
-- album_image_group_rel 那一行的事——那张关联表现在没有 creator_id，需求里也没要，先不加。
--
-- 【album_group 的"添加人"是建相册的人】改名、上下架、拖拽排序都不改这一列。
ALTER TABLE `album_group`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id（建这本相册的人；改名/上下架不动它）' AFTER `status`;

ALTER TABLE `album_image`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id（第一次上传这张图的人；挂到别的分组不改它）' AFTER `pinned`;
