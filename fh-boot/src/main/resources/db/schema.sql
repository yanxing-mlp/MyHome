-- ============================================================================
-- MyHome 家庭管理系统 - 完整数据库初始化脚本
-- ============================================================================
-- 版本: v18 (2026-09-23)
-- 数据库: MySQL 8.0+
-- 字符集: utf8mb4 / utf8mb4_0900_ai_ci
-- 说明: 本脚本整合了所有 Flyway 迁移脚本，用于快速初始化数据库
--       生产环境建议使用 Flyway 逐版本迁移以确保数据一致性
-- ============================================================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `family_home` 
  DEFAULT CHARACTER SET utf8mb4 
  COLLATE utf8mb4_0900_ai_ci;

USE `family_home`;

-- ============================================================================
-- 1. 用户域 (V5xx) - app_user
-- ============================================================================

CREATE TABLE `app_user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`            VARCHAR(32)     NOT NULL                COMMENT '昵称，如 大宝。两端展示、"添加人"都显示它',
  `phone`           VARCHAR(32)     NOT NULL                COMMENT '登录时校验的手机号，明文（不是凭据）',
  `avatar_file_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '头像，指向 file_object；为空时两端给默认头像',
  `role`            VARCHAR(16)     NOT NULL DEFAULT 'MEMBER' COMMENT 'ADMIN=可管理账号 / MEMBER=普通成员',
  `password_hash`   VARCHAR(128)    NOT NULL                COMMENT 'PBKDF2-HMAC-SHA256 哈希口令',
  `deleted`         TINYINT         NOT NULL DEFAULT 0,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_deleted_id` (`deleted`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全家成员（两态删除）';

-- 种子数据：两个初始账号
INSERT INTO `app_user` (`name`, `phone`, `role`, `password_hash`) VALUES
  ('大宝', '15170212308', 'ADMIN', 'pbkdf2_sha256$200000$salt$hash_placeholder'),
  ('小宝', '15079077917', 'MEMBER', 'pbkdf2_sha256$200000$salt$hash_placeholder');

-- ============================================================================
-- 2. 文件域 (V1xx) - file_object, file_category
-- ============================================================================

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
  `biz_type`    VARCHAR(32)     NOT NULL                COMMENT 'ALBUM_IMAGE / RECIPE_IMAGE / USER_AVATAR / DOCUMENT / VIDEO',
  `category_id` BIGINT UNSIGNED DEFAULT NULL            COMMENT '文件分类，仅 biz_type=document 使用；图片为 NULL',
  `scope`       VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/PRIVATE 分区',
  `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'PRIVATE 分区的属主账号 ID，PUBLIC 为 0',
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '上传者 ID',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '1=物理文件已删除，记录仅留审计',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_key` (`file_key`),
  KEY `idx_md5` (`md5`),
  KEY `idx_biz_create` (`biz_type`, `create_time`),
  KEY `idx_biz_category` (`biz_type`, `category_id`),
  KEY `idx_scope_owner` (`scope`, `owner_id`),
  CONSTRAINT `chk_file_object_private_partition` CHECK (`scope`='PUBLIC' OR BINARY `biz_type` IN ('document','video'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件对象';

CREATE TABLE `file_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '分类名：文档/数据/其他',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件分类字典（硬删除 + uk_name）';

-- 初始化文件分类
INSERT INTO `file_category` (`name`) VALUES ('文档'), ('数据'), ('其他');

-- ============================================================================
-- 3. 相册域 (V2xx) - album_group, album_image, album_image_group_rel, album_city
-- ============================================================================

CREATE TABLE `album_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)     NOT NULL                COMMENT '分组名，如 2026 春节',
  `sort`        INT             NOT NULL DEFAULT 0      COMMENT '越大越靠前；新建时取 max(sort)+1',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF（PERSONAL 恒为 ON_SHELF）',
  `scope`       VARCHAR(16)     NOT NULL DEFAULT 'FAMILY' COMMENT 'FAMILY/PERSONAL 分区',
  `creator_id`  BIGINT UNSIGNED NOT NULL                COMMENT '创建者 ID（PERSONAL 即属主）',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_deleted_sort` (`status`, `sort`),
  KEY `idx_scope_creator` (`scope`, `creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相册分组（三态状态）';

CREATE TABLE `album_image` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_id`     BIGINT UNSIGNED NOT NULL                COMMENT '图片',
  `city`        VARCHAR(32)     DEFAULT NULL            COMMENT '城市，自由文本；候选来自 SELECT DISTINCT city',
  `lng`         DECIMAL(10,7)   DEFAULT NULL            COMMENT 'EXIF GPS 经度，一期只存不用',
  `lat`         DECIMAL(10,7)   DEFAULT NULL            COMMENT 'EXIF GPS 纬度，一期只存不用',
  `shoot_time`  DATETIME(3)     DEFAULT NULL            COMMENT '拍摄时间，取 EXIF；无则等于 create_time',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF/DELETED',
  `scope`       VARCHAR(16)     NOT NULL DEFAULT 'FAMILY' COMMENT 'FAMILY/PERSONAL 分区',
  `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'PRIVATE 分区的属主账号 ID，FAMILY 为 0',
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '上传者 ID',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `create_time`),
  KEY `idx_city` (`city`),
  KEY `idx_file` (`file_id`),
  KEY `idx_geo` (`lng`, `lat`),
  KEY `idx_scope_owner` (`scope`, `owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相册图片（三态状态）';

CREATE TABLE `album_image_group_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `image_id`    BIGINT UNSIGNED NOT NULL                COMMENT '相册图片 ID',
  `group_id`    BIGINT UNSIGNED NOT NULL                COMMENT '相册分组 ID',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_image_group` (`image_id`, `group_id`),
  KEY `idx_group` (`group_id`),
  CONSTRAINT `fk_rel_image` FOREIGN KEY (`image_id`) REFERENCES `album_image` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rel_group` FOREIGN KEY (`group_id`) REFERENCES `album_group` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='相册图片-分组关联表';

CREATE TABLE `album_city` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `city`        VARCHAR(32)     NOT NULL                COMMENT '城市名',
  `count`       INT             NOT NULL DEFAULT 0      COMMENT '该城市在架图片数量',
  `scope`       VARCHAR(16)     NOT NULL DEFAULT 'FAMILY' COMMENT 'FAMILY/PERSONAL 分区',
  `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'PRIVATE 分区的属主账号 ID，FAMILY 为 0',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_city_owner` (`scope`, `city`, `owner_id`),
  KEY `idx_scope_owner` (`scope`, `owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相册城市分布统计';

-- ============================================================================
-- 4. 菜谱域 (V3xx) - recipe, recipe_image, recipe_category, recipe_category_rel, 
--                    recipe_practice_group, recipe_practice_option, recipe_practice_rel,
--                    recipe_cart_item, recipe_order, recipe_order_item, 
--                    recipe_cart_state, recipe_cart_checkout
-- ============================================================================

CREATE TABLE `recipe` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)     NOT NULL                COMMENT '菜名',
  `description` TEXT            DEFAULT NULL            COMMENT '做法描述',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF/DELETED',
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '添加者 ID',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_update` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱';

CREATE TABLE `recipe_image` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `file_id`     BIGINT UNSIGNED NOT NULL,
  `sort`        INT             NOT NULL DEFAULT 0      COMMENT '越小越前，sort 最小的是封面',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_sort` (`recipe_id`, `sort`),
  KEY `idx_recipe` (`recipe_id`),
  KEY `idx_file` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱图片（多张，物理删除 + 全量覆盖）';

CREATE TABLE `recipe_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '分类名：荤/素/汤/其他',
  `sort_order`  INT             NOT NULL DEFAULT 0      COMMENT 'C 端菜单展示顺序，越小越前',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱分类字典（硬删除 + 级联解绑）';

CREATE TABLE `recipe_category_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `category_id` BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_category` (`recipe_id`, `category_id`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱-分类关联（一菜一分组）';

CREATE TABLE `recipe_practice_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '分组名：辣度/糖',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='做法分组字典（硬删除 + 级联删选项和解绑）';

CREATE TABLE `recipe_practice_option` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_id`    BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '选项名：不辣/微辣/好辣',
  `is_default`  TINYINT         NOT NULL DEFAULT 0      COMMENT '是否默认选项（组内唯一）',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_name` (`group_id`, `name`),
  KEY `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='做法选项（组内）';

CREATE TABLE `recipe_practice_rel` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`        BIGINT UNSIGNED NOT NULL,
  `group_id`         BIGINT UNSIGNED NOT NULL,
  `required`         TINYINT         NOT NULL DEFAULT 0  COMMENT '是否必选（1=必须选一个做法才能加购）',
  `default_option_id` BIGINT UNSIGNED DEFAULT NULL       COMMENT '默认选项 ID',
  `create_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_group` (`recipe_id`, `group_id`),
  KEY `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱-做法分组关联（多对多，新建菜品时勾选）';

CREATE TABLE `recipe_cart_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `qty`         INT             NOT NULL DEFAULT 1,
  `practices`   VARCHAR(500)    DEFAULT NULL            COMMENT '所选做法 JSON，NULL=未选',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe` (`recipe_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车条目（一菜一行）';

CREATE TABLE `recipe_cart_state` (
  `id`          BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `version`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车状态（固定 id=1，版本号控制并发）';

CREATE TABLE `recipe_cart_checkout` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `cart_version`  BIGINT UNSIGNED NOT NULL,
  `order_id`      BIGINT UNSIGNED DEFAULT NULL          COMMENT '下单产生的订单 ID，NULL=加菜目标',
  `append_target` BIGINT UNSIGNED DEFAULT NULL          COMMENT '继续加菜的目标订单 ID',
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_version` (`cart_version`),
  KEY `idx_order` (`order_id`),
  KEY `idx_append` (`append_target`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车消费回执（幂等控制）';

CREATE TABLE `recipe_order` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING/COMPLETED/CANCELLED',
  `total_qty`   INT             NOT NULL DEFAULT 0         COMMENT '合计份数（下单时购物车快照）',
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL               COMMENT '下单人 ID',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点餐订单（整单车快照）';

CREATE TABLE `recipe_order_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT UNSIGNED NOT NULL,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `recipe_name` VARCHAR(64)     NOT NULL                COMMENT '菜名快照',
  `cover_url`   VARCHAR(255)    DEFAULT NULL            COMMENT '菜品封面快照',
  `qty`         INT             NOT NULL,
  `practices`   VARCHAR(500)    DEFAULT NULL            COMMENT '所选做法 JSON 快照，NULL=未选',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_recipe` (`recipe_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细（菜品+做法快照）';

-- ============================================================================
-- 5. 密码本域 (V4xx) - vault_account
-- ============================================================================

CREATE TABLE `vault_account` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`           VARCHAR(64)     NOT NULL                COMMENT '平台名，如 微信 / steam / QQ',
  `account`        VARCHAR(128)    NOT NULL                COMMENT '账号 / 邮箱 / 手机号，明文（本身不是机密，且要能搜索）',
  `password_enc`   VARCHAR(1024)   NOT NULL                COMMENT '密码密文，任何查询接口都不返回它（实体上标了 select=false）',
  `scope`          VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/PRIVATE 分区',
  `owner_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT 'PRIVATE 分区的属主账号 ID，PUBLIC 为 0',
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL            COMMENT '添加者 ID',
  `deleted`        TINYINT         NOT NULL DEFAULT 0,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_deleted_update` (`deleted`, `update_time`),
  KEY `idx_scope_owner` (`scope`, `owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='密码本（两态删除，密码加密存储）';

-- ============================================================================
-- 索引与约束补充说明
-- ============================================================================
-- 1. name/phone 不建唯一索引：软删表，允许同名重建
-- 2. md5 用普通索引而非唯一索引：软删允许多个相同 md5 存在
-- 3. 所有外键约束仅在开发环境启用，生产环境建议应用层保证一致性
-- 4. CHECK 约束需要 MySQL 8.0.16+ 才生效
-- ============================================================================
