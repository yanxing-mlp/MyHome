-- 分组、图片、城市按 FAMILY / PERSONAL + owner 隔离。
-- FAMILY owner_id = 0；PERSONAL owner_id = 分组 creator_id；图片 creator_id 仍为上传人。
-- 升级期间必须停写并备份：MySQL DDL 隐式提交，后面的拆分 DML 单独放在一个事务内。
-- 历史跨分区共图：优先家庭保留原 image id，其他分区复制元数据后重接原关系。
-- 只共用不可变 file_id；代码删除前检查全相册活引用，不能删一侧就清掉另一侧文件。
-- 没有任何关系的旧图片没有可追溯私人归属，保留在 FAMILY；不猜测上传人就是属主。

-- 先拒绝无法确定属主的异常个人分组，避免把私图悄悄搬到家庭。
CREATE TEMPORARY TABLE album_v211_owner_guard (
  owner_id BIGINT UNSIGNED NOT NULL CHECK (owner_id > 0)
);
INSERT INTO album_v211_owner_guard (owner_id)
SELECT creator_id FROM album_group WHERE scope = 'PERSONAL';
DROP TEMPORARY TABLE album_v211_owner_guard;

ALTER TABLE album_image
  ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FAMILY' COMMENT 'FAMILY=家庭 / PERSONAL=个人，不可修改' AFTER file_id,
  ADD COLUMN owner_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '家庭为0，个人为属主账号，与上传人分开' AFTER scope,
  ADD KEY idx_partition_status (scope, owner_id, status),
  ADD CONSTRAINT chk_image_partition CHECK (
    (scope = 'FAMILY' AND owner_id = 0) OR (scope = 'PERSONAL' AND owner_id > 0)
  );

ALTER TABLE album_city
  ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FAMILY' COMMENT '相册数据分区' AFTER id,
  ADD COLUMN owner_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '家庭为0，个人为属主账号' AFTER scope,
  DROP INDEX uk_city,
  ADD UNIQUE KEY uk_partition_city (scope, owner_id, city),
  ADD CONSTRAINT chk_city_partition CHECK (
    (scope = 'FAMILY' AND owner_id = 0) OR (scope = 'PERSONAL' AND owner_id > 0)
  );

ALTER TABLE album_group
  MODIFY COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FAMILY'
    COMMENT 'FAMILY=家庭共享 / PERSONAL=本人独享，B/C端均按分区校验',
  ADD CONSTRAINT chk_group_partition CHECK (
    scope = 'FAMILY' OR (scope = 'PERSONAL' AND creator_id IS NOT NULL AND creator_id > 0)
  );

START TRANSACTION;

-- 包括下架/已删除组残留的关系：宁可保留私人归属，也不能把它降级成家庭图片。
CREATE TEMPORARY TABLE album_v211_partitions AS
SELECT DISTINCT r.image_id, g.scope,
       CASE WHEN g.scope = 'FAMILY' THEN 0 ELSE g.creator_id END AS owner_id
  FROM album_image_group_rel r
  JOIN album_group g ON g.id = r.group_id;

-- 一个原图片可以映射到多个分区；同一分区多个分组只生成一张图片。
CREATE TEMPORARY TABLE album_v211_ranked AS
SELECT image_id, scope, owner_id,
       ROW_NUMBER() OVER (PARTITION BY image_id ORDER BY scope, owner_id) AS partition_no
  FROM album_v211_partitions;

SET @album_v211_max_id = (SELECT COALESCE(MAX(id), 0) FROM album_image);
CREATE TEMPORARY TABLE album_v211_mapping AS
SELECT image_id, scope, owner_id,
       CASE WHEN partition_no = 1 THEN image_id
            ELSE @album_v211_max_id + ROW_NUMBER() OVER (ORDER BY image_id, scope, owner_id)
       END AS target_id
  FROM album_v211_ranked;

-- 先复制再改原行；时间、状态、置顶、位置、上传人全部保留。
INSERT INTO album_image
  (id, file_id, scope, owner_id, city, lng, lat, shoot_time, status, pinned,
   creator_id, create_time, update_time)
SELECT m.target_id, i.file_id, m.scope, m.owner_id, i.city, i.lng, i.lat,
       i.shoot_time, i.status, i.pinned, i.creator_id, i.create_time, i.update_time
  FROM album_v211_mapping m
  JOIN album_image i ON i.id = m.image_id
 WHERE m.target_id <> m.image_id;

UPDATE album_image i
  JOIN album_v211_mapping m ON m.image_id = i.id AND m.target_id = i.id
   SET i.scope = m.scope, i.owner_id = m.owner_id, i.update_time = i.update_time;

UPDATE album_image_group_rel r
  JOIN album_group g ON g.id = r.group_id
  JOIN album_v211_mapping m ON m.image_id = r.image_id
       AND m.scope = g.scope
       AND m.owner_id = CASE WHEN g.scope = 'FAMILY' THEN 0 ELSE g.creator_id END
   SET r.image_id = m.target_id, r.update_time = r.update_time;

-- 清掉历史失效关系但保留已推导出的分区；已删图片仍不复活。
DELETE r FROM album_image_group_rel r
  JOIN album_group g ON g.id = r.group_id
  JOIN album_image i ON i.id = r.image_id
 WHERE g.status = 'DELETED' OR i.status = 'DELETED';

-- 城市是派生数据；与写入服务同一口径：只统计在架图片，分组上下架不影响统计。
DELETE FROM album_city;
INSERT INTO album_city (scope, owner_id, city, count)
SELECT scope, owner_id, city, COUNT(*)
  FROM album_image
 WHERE status = 'ON_SHELF' AND city IS NOT NULL AND TRIM(city) <> ''
 GROUP BY scope, owner_id, city;

DROP TEMPORARY TABLE album_v211_mapping;
DROP TEMPORARY TABLE album_v211_ranked;
DROP TEMPORARY TABLE album_v211_partitions;
COMMIT;
