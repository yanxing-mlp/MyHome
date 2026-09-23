-- NULL 不参与唯一冲突，支持多次删号重建；不限制口令哈希或初始密码。
ALTER TABLE app_user
    ADD COLUMN unique_name VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(deleted = 0, TRIM(name), NULL)) STORED,
    ADD COLUMN unique_phone VARCHAR(32) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(deleted = 0, TRIM(phone), NULL)) STORED,
    ADD UNIQUE KEY uk_user_live_name (unique_name),
    ADD UNIQUE KEY uk_user_live_phone (unique_phone);
