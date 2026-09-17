-- 菜谱标签与类型字典初始化（方案 §4.2）
-- 用 INSERT ... SELECT WHERE NOT EXISTS 而不是裸 INSERT，
-- 这样即使有人在 Flyway 之外手动插过同名数据，迁移也不会因 uk_name 冲突而失败。

INSERT INTO `recipe_tag` (`name`)
SELECT '午餐' WHERE NOT EXISTS (SELECT 1 FROM `recipe_tag` WHERE `name` = '午餐');
INSERT INTO `recipe_tag` (`name`)
SELECT '晚餐' WHERE NOT EXISTS (SELECT 1 FROM `recipe_tag` WHERE `name` = '晚餐');
INSERT INTO `recipe_tag` (`name`)
SELECT '早餐' WHERE NOT EXISTS (SELECT 1 FROM `recipe_tag` WHERE `name` = '早餐');

INSERT INTO `recipe_type` (`name`)
SELECT '荤' WHERE NOT EXISTS (SELECT 1 FROM `recipe_type` WHERE `name` = '荤');
INSERT INTO `recipe_type` (`name`)
SELECT '素' WHERE NOT EXISTS (SELECT 1 FROM `recipe_type` WHERE `name` = '素');
INSERT INTO `recipe_type` (`name`)
SELECT '汤' WHERE NOT EXISTS (SELECT 1 FROM `recipe_type` WHERE `name` = '汤');
INSERT INTO `recipe_type` (`name`)
SELECT '其他' WHERE NOT EXISTS (SELECT 1 FROM `recipe_type` WHERE `name` = '其他');
