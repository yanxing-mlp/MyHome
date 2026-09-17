-- recipe 域（版本号段 V3xx）

-- 菜谱主表：三态 status，@TableLogic 用不了，查询必须手写 status <> 'DELETED'。
-- v4 起删除菜谱会一并物理删除图片文件，所以 DELETED 不可恢复。
-- 不存 cover_file_id：封面 = recipe_image 里 sort 最小的那张（方案 §4.3）。
CREATE TABLE `recipe` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)     NOT NULL                COMMENT '菜名',
  `description` TEXT            DEFAULT NULL            COMMENT '做法描述',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF/OFF_SHELF/DELETED',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_update` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱';

-- 菜谱图片：一对多，物理删除 + 全量覆盖式保存。无单图描述字段（用户明确不要）。
-- uk_recipe_sort 保证同一菜谱内 sort 不重复，封面（sort 最小）唯一确定；
-- 全量覆盖是先 delete 再 insert，不会撞唯一键。
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

-- 标签字典：硬删除 + 级联解绑（删标签时先清 recipe_tag_rel 再删字典，一个事务）。
-- 硬删 + uk_name 最干净，名字还能复用；软删除会和 uk_name 冲突。
CREATE TABLE `recipe_tag` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '标签名：午餐/晚餐/早餐',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱标签字典（硬删除 + 级联解绑）';

CREATE TABLE `recipe_type` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '类型名：荤/素/汤/其他',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱类型字典（硬删除 + 级联解绑）';

-- 多对多关联。过滤分页必须用 EXISTS 子查询而不是 JOIN + DISTINCT（方案 §5.6）。
CREATE TABLE `recipe_tag_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `tag_id`      BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_tag` (`recipe_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱-标签关联（多对多）';

CREATE TABLE `recipe_type_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `type_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_type` (`recipe_id`, `type_id`),
  KEY `idx_type` (`type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱-类型关联（多对多）';
