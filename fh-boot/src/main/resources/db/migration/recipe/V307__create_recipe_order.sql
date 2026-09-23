-- 订单（V3xx 号段续）。家庭共用单车：下单 = 把整个购物车快照成一单，随后清空购物车。
-- 订单行冗余存菜名/做法快照：菜品改名、删除都不影响历史订单展示。
-- status 一期只有 PENDING（待制作），不做状态流转，列先留下。

CREATE TABLE `recipe_order` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '订单状态，当前仅 PENDING=待制作',
  `total_qty`   INT             NOT NULL DEFAULT 0         COMMENT '合计份数（下单时购物车快照）',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点餐订单（整单车快照）';

CREATE TABLE `recipe_order_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT UNSIGNED NOT NULL,
  `recipe_id`   BIGINT UNSIGNED NOT NULL,
  `recipe_name` VARCHAR(64)     NOT NULL COMMENT '菜名快照',
  `qty`         INT             NOT NULL,
  `practices`   VARCHAR(500)    DEFAULT NULL COMMENT '所选做法 JSON 快照，NULL=未选',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_recipe` (`recipe_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细（菜品+做法快照）';
