-- 相册图片-分组关联表（支持一张图片属于多个分组）
-- 方案 §5.3：图片可被添加到多个分组，但城市只能有一个（存储在 album_image.city）
CREATE TABLE `album_image_group_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `image_id`    BIGINT UNSIGNED NOT NULL                COMMENT '相册图片 ID',
  `group_id`    BIGINT UNSIGNED NOT NULL                COMMENT '相册分组 ID',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  -- 同一张图片在同一分组内不重复
  UNIQUE KEY `uk_image_group` (`image_id`, `group_id`),
  KEY `idx_group` (`group_id`),
  CONSTRAINT `fk_rel_image` FOREIGN KEY (`image_id`) REFERENCES `album_image` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rel_group` FOREIGN KEY (`group_id`) REFERENCES `album_group` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='相册图片-分组关联表';

-- 将现有 album_image.group_id 数据迁移到关联表
INSERT INTO `album_image_group_rel` (`image_id`, `group_id`)
SELECT `id`, `group_id` FROM `album_image` WHERE `group_id` IS NOT NULL;

-- 注意：保留 album_image.group_id 作为"主分组"用于向后兼容，
-- 但查询图片所属分组时优先使用 album_image_group_rel 表
