-- 路径及磁盘身份一经登记不再修改；id=1 专门承接所有历史无 namespace 的 key。
-- 1024 个 utf8mb4 字符超过 InnoDB 的完整索引长度，使用完整规范路径的 SHA-256 唯一索引。
-- Windows 路径由 Registry 转为真实路径并统一大小写后持久化，不使用截断的前缀唯一索引。
CREATE TABLE `file_storage_directory` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `path`                 VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `path_hash`            BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(`path`, 256))) STORED,
  `marker`               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                         COMMENT '磁盘身份 UUID，不通过 API 返回',
  `is_default`           TINYINT NOT NULL DEFAULT 0,
  `default_slot`         TINYINT GENERATED ALWAYS AS (CASE WHEN `is_default` = 1 THEN 1 ELSE NULL END) STORED,
  `initialization_state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  `public_identity`      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
                         COMMENT '初始化恢复用的目录 fileKey/文件系统指纹，不是容量缓存',
  `private_identity`     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `create_time`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_storage_directory_path` (`path_hash`),
  UNIQUE KEY `uk_file_storage_directory_marker` (`marker`),
  UNIQUE KEY `uk_file_storage_directory_default` (`default_slot`),
  CONSTRAINT `chk_file_storage_directory_default` CHECK (`is_default` IN (0, 1)),
  CONSTRAINT `chk_file_storage_directory_state` CHECK (`initialization_state` IN ('PENDING', 'READY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可迁移的文件存储目录登记';
