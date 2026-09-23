-- user 域（V5xx 号段续）：购物车从"一菜一行"改成"一人一菜一行"。
--
-- 原先 recipe_cart_item 的唯一键是 uk_recipe(recipe_id)，全车一道菜只允许一行。两个人加购同一道菜会并进
-- 同一行（份数累加、加购人留在先加的那个人身上），v20 起抽屉/确认页按加购人分模块展示后，同一道菜被合并
-- 就意味着两个人的份数挤在一个模块里分不清。本轮把唯一键换成 uk_recipe_creator(recipe_id, creator_id)：
-- 同菜不同加购人各占一行，改量/改做法/减到 0 都只动自己那一行。
--
-- 【为什么放在 user 域，而不是跟着 recipe 号段写】
-- 和 V501/V504 完全同一个理由：NOT NULL 之前必须先把存量的 NULL creator_id 洗掉，而洗数据要读 app_user，
-- app_user 是 V500 建的。recipe 号段（V3xx）在从零重建时排在 V500 前面，那时 app_user 还不存在，
-- 子查询会直接报错。所以这条既洗数据又改约束的迁移统一放在 V500 之后。
-- 存量 NULL 的来源：V504 洗过一次，但此后"再来一单"的插入分支没带 creator_id，又可能产生新的 NULL 行。
--
-- 【为什么新唯一键可以直接加】
-- 洗完之后 creator_id 全部非空；旧唯一键一菜一行，同 (recipe_id, creator_id) 必然没有重复，可以直接建。
SET @daibao := (SELECT `id` FROM `app_user` WHERE `name` = '大宝' AND `deleted` = 0 LIMIT 1);

UPDATE `recipe_cart_item` SET `creator_id` = @daibao WHERE `creator_id` IS NULL;

ALTER TABLE `recipe_cart_item`
  MODIFY `creator_id` BIGINT UNSIGNED NOT NULL        COMMENT '加购人（app_user.id）；一人一菜一行，改量只改自己那行',
  DROP INDEX `uk_recipe`,
  ADD UNIQUE KEY `uk_recipe_creator` (`recipe_id`, `creator_id`);

ALTER TABLE `recipe_cart_item` COMMENT='点餐购物车条目（一人一菜一行：同菜不同加购人各占一行）';
