-- user 域（V5xx 号段续）：洗购物车条目的"加购人"存量。
--
-- 口径与 V501 完全一致：按昵称子查询取大宝的 id（不写死 1）、WHERE creator_id IS NULL 保证可重放、
-- 查不到大宝时子查询返回 NULL，那些行仍是 NULL（前端整段不渲染），不会写成不存在的 id。
--
-- 【为什么这条洗数据放在 user 域，而不是跟着 recipe/V319 一起写】
-- 回填要读 app_user，而 app_user 是 V500 建的。V319 靠 out-of-order 在已有库上补跑时 app_user 确实存在，
-- 但从零重建时迁移按版本号顺序执行，V319 排在 V500 前面，那时 app_user 还不存在，子查询会直接报错。
-- 所以 recipe/V319 只负责加列，存量数据统一在 V500 之后洗——和 V501 是同一个理由。
-- 将来真按域拆库，这个文件要跟着各自的库拆开跑，它是一次性脚本，不是长期口径。
SET @daibao := (SELECT `id` FROM `app_user` WHERE `name` = '大宝' AND `deleted` = 0 LIMIT 1);

UPDATE `recipe_cart_item` SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
