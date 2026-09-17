-- album 域（版本号段 V2xx）

-- 相册分组：两态删除，可以挂 MyBatis-Plus @TableLogic。
-- 刻意不加 description / cover_file_id / album_date（用户只要 name + 排序）。
-- 封面不存字段，列表接口批量子查询取该分组最新一张上架图（方案 §4.3）。
CREATE TABLE `album_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)     NOT NULL                COMMENT '分组名，如 2026 春节',
  `sort`        INT             NOT NULL DEFAULT 0      COMMENT '越大越靠前；新建时取 max(sort)+1',
  `deleted`     TINYINT         NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_deleted_sort` (`deleted`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相册分组（两态删除）';

-- 相册图片：一等实体，三态 status。
-- 注意 @TableLogic 在这里用不了，所有查询必须手写 status <> 'DELETED'（方案 §10 风险 5）。
-- lng/lat 一期只存不用，为将来的地图视图和自动填城市留数据；
-- DECIMAL(10,7) 约 1cm 精度，用 float/double 会精度漂移导致地图点位抖动。
CREATE TABLE `album_image` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属相册分组',
  `file_id`     BIGINT UNSIGNED NOT NULL                COMMENT '图片',
  `city`        VARCHAR(32)     DEFAULT NULL            COMMENT '城市，自由文本；候选来自 SELECT DISTINCT city',
  `lng`         DECIMAL(10,7)   DEFAULT NULL            COMMENT 'EXIF GPS 经度，一期只存不用',
  `lat`         DECIMAL(10,7)   DEFAULT NULL            COMMENT 'EXIF GPS 纬度，一期只存不用',
  `shoot_time`  DATETIME(3)     DEFAULT NULL            COMMENT '拍摄时间，取 EXIF；无则等于 create_time',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF/DELETED',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_group_status_time` (`group_id`, `status`, `create_time`),
  KEY `idx_city` (`city`),
  -- 跨域查 md5 做同分组查重时，用 file_id 反查（方案 §6.9）
  KEY `idx_file` (`file_id`),
  KEY `idx_geo` (`lng`, `lat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相册图片（三态状态）';
