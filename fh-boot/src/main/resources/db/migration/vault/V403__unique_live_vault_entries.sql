-- 共享密码本按名称 + 账号判重，允许同平台多账号、不同平台同账号；不比较密码。
ALTER TABLE vault_account
    ADD COLUMN unique_name VARCHAR(64) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(deleted = 0, TRIM(name), NULL)) STORED,
    ADD COLUMN unique_account VARCHAR(128) COLLATE utf8mb4_0900_ai_ci
        GENERATED ALWAYS AS (IF(deleted = 0, TRIM(account), NULL)) STORED,
    ADD UNIQUE KEY uk_vault_live_entry (unique_name, unique_account);
