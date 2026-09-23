-- file 域（版本号段 V1xx）
-- 文件分类字典：B 端"文件管理"页的分类下拉框用。
-- 与 recipe_tag / recipe_practice_group 同口径：硬删除 + uk_name（软删会和 uk_name 打架）。
-- 刻意不做 sort_order：文件分类没有"C 端菜单顺序"这类展示语义，按 id 排即可。

CREATE TABLE `file_category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)     NOT NULL                COMMENT '分类名：文档/数据/其他',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件分类字典（硬删除 + uk_name）';

-- 初始化三个常用分类；不够用时下拉框里可以直接内联新建，不需要迁移。
INSERT INTO `file_category` (`name`) VALUES ('文档'), ('数据'), ('其他');

-- 文档文件复用 file_object，不另建业务表：
--   biz_type 多一个取值 document（图片行是 album / recipe）
--   category_id 只有 document 行使用，图片行留 NULL
-- 文件类型（csv/md/doc/docx）不单独存列：扩展名 ext 已有，上传时按"扩展名 + 真实字节"
-- 双重解析并校验（见 DocumentFileType），浏览器给的 Content-Type 一律不信。
ALTER TABLE `file_object`
  ADD COLUMN `category_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '文件分类，仅 biz_type=document 使用；图片为 NULL' AFTER `biz_type`,
  ADD KEY `idx_biz_category` (`biz_type`, `category_id`);
