-- 文件/视频分组：一张表 + biz_type 区分「文件分组」与「视频分组」两类。
-- 与 file_object 同域、同分区口径（PUBLIC/0 全家共享、PRIVATE/当前账号隔离，属主由服务端按登录态设定）。
--
-- 刻意用硬删除（与 file_category 字典一致），不用 file_object 那种两态 deleted：
-- 删分组时靠 file_group_rel 的 group_id 外键级联清掉关联行，不留相册 V205 那种孤儿关联，
-- 名称唯一性范围 = 同 biz_type + 同分区，删除后同名可重建（行没了，生成列自然不再占名）。
CREATE TABLE `file_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type`    VARCHAR(16)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                                COMMENT '分组所属业务：document 文件 / video 视频',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '分组名',
  `sort`        INT             NOT NULL DEFAULT 0      COMMENT '越大越靠前；新建取 max(sort)+1',
  `scope`       VARCHAR(16)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC'
                                COMMENT '分区：PUBLIC/PRIVATE',
  `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'PUBLIC为0，PRIVATE为所属账号',
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '添加人（app_user.id）；PRIVATE 行即属主',
  `unique_name` VARCHAR(64)     COLLATE utf8mb4_0900_ai_ci
                GENERATED ALWAYS AS (TRIM(name)) STORED COMMENT '去首尾空格后的名称，仅用于唯一约束',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_group_name` (`biz_type`, `scope`, `owner_id`, `unique_name`),
  KEY `idx_file_group_partition` (`scope`, `owner_id`, `biz_type`, `sort`),
  CONSTRAINT `chk_file_group_partition` CHECK (
      (scope = 'PUBLIC' AND owner_id = 0)
      OR (scope = 'PRIVATE' AND owner_id > 0)
  ),
  CONSTRAINT `chk_file_group_biz_type` CHECK (biz_type IN ('document', 'video'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件/视频分组';
