-- file 域（版本号段 V1xx）
-- 文件对象。技术实体，只有"在/不在"两态，没有内容可见性语义，
-- 所以用 deleted 而不是三态 status —— 这是有意的不一致（方案 §4.3）。

CREATE TABLE `file_object` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_key`    VARCHAR(255)    NOT NULL                COMMENT '相对存储根的路径，如 2026/09/17/uuid.jpg',
  `thumb_key`   VARCHAR(255)    DEFAULT NULL            COMMENT '缩略图相对路径，生成失败则为空',
  `origin_name` VARCHAR(255)    NOT NULL                COMMENT '上传时原始文件名',
  `md5`         CHAR(32)        NOT NULL                COMMENT '文件内容 MD5，秒传去重用',
  `mime_type`   VARCHAR(64)     NOT NULL,
  `ext`         VARCHAR(16)     NOT NULL,
  `file_size`   BIGINT UNSIGNED NOT NULL                COMMENT '字节',
  `width`       INT UNSIGNED    DEFAULT NULL,
  `height`      INT UNSIGNED    DEFAULT NULL,
  `hard_link`   TINYINT         NOT NULL DEFAULT 0      COMMENT '1=物理文件是硬链接（秒传产生），排查用',
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT 'ALBUM_IMAGE / RECIPE_IMAGE',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '1=物理文件已删除，记录仅留审计',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_key` (`file_key`),
  -- 普通索引而非唯一索引：file_object 是软删除，唯一索引会让"删掉再传同一张图"冲突，
  -- 改成 uk(md5, deleted) 也不行（同一 md5 删两次就撞）。应用层查 md5 = ? AND deleted = 0。
  KEY `idx_md5` (`md5`),
  KEY `idx_biz_create` (`biz_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件对象';
