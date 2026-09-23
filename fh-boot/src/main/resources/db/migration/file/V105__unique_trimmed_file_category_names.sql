ALTER TABLE file_category
    ADD COLUMN unique_name VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (TRIM(name)) STORED,
    DROP INDEX uk_name,
    ADD UNIQUE KEY uk_file_category_name (unique_name);
