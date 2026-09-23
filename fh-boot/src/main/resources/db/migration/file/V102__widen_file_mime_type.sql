-- file 域（版本号段 V1xx）
-- mime_type 从 VARCHAR(64) 放宽到 128。
--
-- 原因：文档上传支持 docx，它的规范 MIME 是
-- application/vnd.openxmlformats-officedocument.wordprocessingml.document（71 字符），
-- 64 撑不住，插入直接被 MySQL 截断报错（Data too long for column 'mime_type'）。
-- 选择放宽而不是把 docx 记成一个短假 MIME：这一列就是给下游判类型用的，写实值才有意义。
-- 64→128 在 MySQL 8 里是元数据级变更，不重写行。

ALTER TABLE `file_object`
  MODIFY COLUMN `mime_type` VARCHAR(128) NOT NULL;
