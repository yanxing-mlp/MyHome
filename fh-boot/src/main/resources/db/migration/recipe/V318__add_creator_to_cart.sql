-- 购物车条目加 creator_id（记录是谁加的这道菜）。
ALTER TABLE `recipe_cart_item` ADD COLUMN `creator_id` BIGINT UNSIGNED COMMENT '加购人 ID' AFTER `qty`;
