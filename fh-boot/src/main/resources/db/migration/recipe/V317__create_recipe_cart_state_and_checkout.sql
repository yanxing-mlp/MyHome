-- 家庭共用单车：以固定持久行串行化所有购物车读写与消费，不按账号分车。
-- 版本初始为 0；改车、清车、再来一单、下单、加菜成功后均在同一事务推进版本。
CREATE TABLE `recipe_cart_state` (
  `id`          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '固定为 1，家庭共用单车锁',
  `version`     BIGINT           NOT NULL DEFAULT 0 COMMENT '当前购物车版本',
  `create_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_recipe_cart_state_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='共享购物车版本与持久行锁';

INSERT INTO `recipe_cart_state` (`id`, `version`) VALUES (1, 0);

-- 一份购物车版本至多消费一次。无外键；回执有意在订单物理删除后继续保留，禁止随删单清理，
-- 否则旧请求可能重放成新订单。重试发现原订单已删除时返回业务 notFound，不重建订单。
CREATE TABLE `recipe_cart_checkout` (
  `cart_version`    BIGINT          NOT NULL COMMENT '已消费的购物车版本',
  `order_id`        BIGINT UNSIGNED NOT NULL COMMENT '消费落入的订单 ID，删单后仍保留',
  `target_order_id` BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL=create；非空=append 的目标订单 ID',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`cart_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='共享购物车消费回执（防重放，不随订单删除）';
