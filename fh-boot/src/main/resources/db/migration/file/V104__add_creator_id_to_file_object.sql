-- file 域（V1xx 号段续）：file_object 加"添加人"。
--
-- 【口径】七个业务表各加一列 creator_id，语义统一为"这一行是谁建的"，指向 app_user.id（V500）。
-- 相册/菜谱/文件/密码本四个域都只存 id、不存昵称，也不跨域查名字：昵称由前端拿账号字典
-- （GET /api/b/user/options）现查——与做法选项"名字不进接口、由 admin 现查做法字典"同一口径。
-- 这样四个域都不必新增对 user 域的依赖，域边界仍是原来那几条边。
--
-- 【可空，不是 NOT NULL】写入路径由 CurrentUserInterceptor 挡在门口（缺 X-User-Id 直接 401），
-- 正常情况下不会有 NULL。留 NULL 是为了不让"某条写入路径漏填"变成一条 500 的 SQL 错误——
-- 那种坑排查成本远高于它换来的约束收益；存量行则由 V501 一次性洗成大宝。
--
-- 【不加索引】家庭量级这几张表都是个位到百行，且当前没有任何"只看某人加的数据"的筛选需求。
-- 真要按人筛时再补 (creator_id, create_time)，届时也是新开一条迁移。
--
-- 【biz_type 的取值多了一个】USER_AVATAR（头像上传）。biz_type 只是归属标签、服务端不做枚举校验，
-- 唯一的等值判断是文档列表那句 = 'document'，所以这里不需要动 DDL 也不需要改注释之外的代码。
ALTER TABLE `file_object`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id；NULL=未知/存量（已由 V501 洗成大宝）' AFTER `biz_type`;
