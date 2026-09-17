-- album 域（版本号段 V2xx）

-- v5 需求：分组内图片支持「置顶」。
--
-- 为什么是一个布尔而不是 sort 字段：
--   置顶的语义只有"钉在最前"，没有"钉到第 3 个"。用 sort 就得维护一组连续整数
--   （置顶/取消置顶都要重排），而布尔值一个 UPDATE 就够，且和分组级的拖拽排序
--   （album_group.sort）是两套互不相干的机制，不会互相踩。
--   置顶图之间的相对顺序沿用 create_time DESC，不单独给置顶排序 —— 需要时再加。
--
-- 索引刻意**不改**：查询条件是 `status <> 'DELETED'`，这是 range 谓词，
-- 任何以 status 打头的复合索引都用不上有序性，加 (group_id, status, pinned, create_time)
-- 并不能消除 filesort，家庭相册单分组千级数据量 filesort 完全无所谓。
ALTER TABLE `album_image`
  ADD COLUMN `pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '1=分组内置顶；置顶图排在未置顶图之前' AFTER `status`;
