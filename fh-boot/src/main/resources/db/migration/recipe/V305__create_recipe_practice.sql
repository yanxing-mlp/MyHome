-- 做法字典：分组（辣度/糖）+ 组内选项（不辣/微辣/好辣、不加糖/加糖）。
-- 菜谱通过 recipe_practice_rel 绑定可选的做法分组（多对多），
-- C 端详情浮层只对绑定的分组展示选项，加购时所选做法随购物车条目落库。
-- 与分类/标签同口径：字典硬删除 + 级联解绑（软删会和 uk_name 冲突）。

CREATE TABLE `recipe_practice_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '分组名：辣度/糖',
  `sort_order`  INT             NOT NULL DEFAULT 0      COMMENT '越小越前',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='做法分组字典（硬删除 + 级联删选项和解绑）';

CREATE TABLE `recipe_practice_option` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_id`    BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '选项名：不辣/微辣/好辣',
  `sort_order`  INT             NOT NULL DEFAULT 0      COMMENT '越小越前，C 端默认选中第一个',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_name` (`group_id`, `name`),
  KEY `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='做法选项（组内）';

CREATE TABLE `recipe_practice_rel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `group_id`    BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_group` (`recipe_id`, `group_id`),
  KEY `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜谱-做法分组关联（多对多，新建菜品时勾选）';

-- 购物车一行仍是"一个菜一行"（uk_recipe 不动），所选做法以 JSON 存列：
-- [{"groupId":1,"optionId":2}]，NULL=未选。换做法 = 覆盖同一行的 practices，不新增行。
ALTER TABLE `recipe_cart_item`
  ADD COLUMN `practices` VARCHAR(500) DEFAULT NULL COMMENT '所选做法 JSON，NULL=未选';
