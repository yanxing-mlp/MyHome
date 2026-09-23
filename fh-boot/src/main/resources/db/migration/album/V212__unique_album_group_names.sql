-- 家庭同名不按创建人拆分；个人按属主拆分。上下架共用名称，删除后可重建。
ALTER TABLE album_group
    ADD COLUMN unique_owner BIGINT UNSIGNED
        GENERATED ALWAYS AS (IF(scope = 'PERSONAL', creator_id, 0)) STORED,
    ADD COLUMN unique_name VARCHAR(64) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(status = 'DELETED', NULL, TRIM(name))) STORED,
    ADD UNIQUE KEY uk_album_group_partition_name (scope, unique_owner, unique_name);
