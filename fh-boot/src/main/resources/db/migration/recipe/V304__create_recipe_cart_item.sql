-- 点餐购物车条目（C 端点餐页加购落库）。
-- 家庭共用单车：无用户体系（方案一期 C 端不登录），一个菜一行，qty>=1，减到 0 物理删行。
-- "下单"功能实现时再考虑订单表 + 清车，这里只存"想买什么"。
CREATE TABLE `recipe_cart_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `recipe_id`   BIGINT UNSIGNED NOT NULL                COMMENT '菜品 ID',
  `qty`         INT             NOT NULL DEFAULT 1      COMMENT '数量，>=1；减到 0 删行',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe` (`recipe_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点餐购物车条目（家庭共用，无用户维度）';
