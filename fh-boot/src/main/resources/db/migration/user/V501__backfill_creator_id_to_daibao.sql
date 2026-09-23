-- user 域（V5xx 号段续）：存量数据洗"添加人" —— 全家已有的东西都算大宝加的。
--
-- 【为什么这一条跨域 UPDATE 放在 user 域，而不是各域自己洗】
-- 各域那四条加列迁移（file/V104、album/V209、recipe/V316、vault/V402）里没法回填大宝的 id：
-- Flyway 按版本号顺序应用待执行迁移，V1xx/2xx/3xx/4xx 全都排在 V500（建 app_user）之前，
-- 那几条跑的时候 app_user 这张表还不存在。所以 schema 各域自己改、数据一次性在这里洗。
-- 将来真按域拆库，这个文件要拆成七段跟着各自的库跑——它是一次性脚本，不是长期口径。
--
-- 【按昵称子查询取 id，不写死 1】V500 的种子让大宝当前就是 id=1，但"第一个自增值永远是 1"
-- 这种事不该被依赖：手动补过数据、或将来换库重放迁移，写死的 1 就悄悄把添加人挂到别人身上，
-- 而且挂得毫无报错。查不到大宝时子查询返回 NULL，那些行的 creator_id 就仍是 NULL（前端不渲染这一行），
-- 不会把添加人写成一个不存在的 id。
--
-- 【只洗存量，不改已洗的】WHERE creator_id IS NULL 让这一条可重放（虽然迁移只跑一次），
-- 也保证万一将来有人手动补了带添加人的行，不会被这里覆盖掉。
SET @daibao := (SELECT `id` FROM `app_user` WHERE `name` = '大宝' AND `deleted` = 0 LIMIT 1);

UPDATE `file_object`     SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `album_group`     SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `album_image`     SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `recipe`          SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `recipe_category` SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `recipe_order`    SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
UPDATE `vault_account`   SET `creator_id` = @daibao WHERE `creator_id` IS NULL;
