-- vault 域（V4xx 号段续）：密码本条目加"添加人"。
--
-- 口径与 file/V104 一致（可空、不建索引、只存 id 不存昵称）。
--
-- 【这一列只回答"谁录的这条口令"】它不是访问控制：本域仍然对任何登录进来的账号可见可改，
-- 一期没有"这条口令只有大宝能看"的需求。真要按人隔离时再谈，别把 creator_id 当成权限列用。
-- 【改名/改账号/换口令都不动它】只有新建那一刻写。
-- 【绝不因为加了这一列就把密文带出来】password_enc 上那个 select=false 是全库最硬的一条规矩，
-- 见 VaultAccountDO 与包注释。
ALTER TABLE `vault_account`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id（谁录的这条口令；改名/换口令不动它）' AFTER `password_enc`;
