-- 下架仍占名；删除释放名称。生成列不修改历史展示名，存量冲突由建索引失败显式暴露。
ALTER TABLE recipe
    ADD COLUMN unique_name VARCHAR(64) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(status = 'DELETED', NULL, TRIM(name))) STORED,
    ADD UNIQUE KEY uk_recipe_live_name (unique_name);

ALTER TABLE recipe_category
    ADD COLUMN unique_name VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (TRIM(name)) STORED,
    DROP INDEX uk_name,
    ADD UNIQUE KEY uk_recipe_category_name (unique_name);

ALTER TABLE recipe_practice_group
    ADD COLUMN unique_name VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (TRIM(name)) STORED,
    DROP INDEX uk_name,
    ADD UNIQUE KEY uk_practice_group_name (unique_name);

ALTER TABLE recipe_practice_option
    ADD COLUMN unique_name VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (TRIM(name)) STORED,
    DROP INDEX uk_group_name,
    ADD UNIQUE KEY uk_practice_option_name (group_id, unique_name);
