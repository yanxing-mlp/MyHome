-- 密码本划分公共 / 私人分区。历史记录默认归 PUBLIC / owner_id=0，
-- 不删改记录、添加人、创建时间或更新时间，也不提供批量迁移分区能力。
-- 保留 V403 的生成列和唯一键名称，让软删复用及重复错误映射继续有效。
ALTER TABLE vault_account
    ADD COLUMN scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC'
        COMMENT '分区 PUBLIC / PRIVATE，创建后不可修改',
    ADD COLUMN owner_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '公共分区为 0，私人分区为所属账号 app_user.id',
    ADD CONSTRAINT chk_vault_account_partition CHECK (
        (scope = 'PUBLIC' AND owner_id = 0)
        OR (scope = 'PRIVATE' AND owner_id > 0)
    ),
    ADD KEY idx_vault_partition_list (scope, owner_id, deleted, update_time, id),
    DROP INDEX uk_vault_live_entry,
    ADD UNIQUE KEY uk_vault_live_entry (scope, owner_id, unique_name, unique_account);
