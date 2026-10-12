-- 文件/视频与分组的多对多关联。file_id 指向 file_object.id（文档/视频没有独立业务表，
-- 与相册 album_image_group_rel 指向 album_image.id 的差异就在这里）。
--
-- group_id 外键级联：分组是硬删除，删组时数据库自动清掉本组关联行。
-- file_id 外键也声明级联，但它<b>不会</b>在删文档/视频时触发——file_object 是软删（deleted=1 行仍在），
-- 所以删文档/视频时必须由服务层显式硬删关联行，见 DocumentFileService.delete / VideoService.delete。
CREATE TABLE `file_group_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_id`     BIGINT UNSIGNED NOT NULL                COMMENT 'file_object.id（文档或视频行）',
  `group_id`    BIGINT UNSIGNED NOT NULL                COMMENT 'file_group.id',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_group_rel` (`file_id`, `group_id`),
  KEY `idx_file_group_rel_group` (`group_id`),
  CONSTRAINT `fk_file_group_rel_file` FOREIGN KEY (`file_id`) REFERENCES `file_object` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_file_group_rel_group` FOREIGN KEY (`group_id`) REFERENCES `file_group` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件/视频-分组关联表';
