-- 文件管理的公共/私人分区；历史文件及分类均保留为 PUBLIC/0。
-- 图片行也固定 PUBLIC/0，但此列不代表相册、菜谱或头像的访问权限。
ALTER TABLE file_object
    ADD COLUMN scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC'
        COMMENT '仅文档使用的分区：PUBLIC/PRIVATE；图片固定 PUBLIC' AFTER category_id,
    ADD COLUMN owner_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'PUBLIC及图片为0，PRIVATE为所属账号' AFTER scope,
    ADD CONSTRAINT chk_file_object_partition CHECK (
        (scope = 'PUBLIC' AND owner_id = 0)
        OR (scope = 'PRIVATE' AND owner_id > 0)
    ),
    ADD CONSTRAINT chk_file_object_private_document CHECK (
        scope = 'PUBLIC' OR BINARY biz_type = 'document'
    ),
    ADD KEY idx_file_object_partition (scope, owner_id, biz_type, deleted, id),
    ADD KEY idx_file_object_partition_category (scope, owner_id, biz_type, deleted, category_id, id),
    ADD KEY idx_file_object_partition_md5 (scope, owner_id, md5, deleted);

ALTER TABLE file_category
    ADD COLUMN scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLIC'
        COMMENT '文档分类分区：PUBLIC/PRIVATE' AFTER name,
    ADD COLUMN owner_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'PUBLIC为0，PRIVATE为所属账号' AFTER scope,
    ADD CONSTRAINT chk_file_category_partition CHECK (
        (scope = 'PUBLIC' AND owner_id = 0)
        OR (scope = 'PRIVATE' AND owner_id > 0)
    ),
    -- 保留 V105 的 TRIM(name) 生成列、排序规则及索引名称，仅缩小唯一性范围。
    DROP INDEX uk_file_category_name,
    ADD UNIQUE KEY uk_file_category_name (scope, owner_id, unique_name),
    ADD KEY idx_file_category_partition (scope, owner_id, id);
