-- 视频管理（2026-09-23）：视频与文档一样是 file_object 的行，用 biz_type='video' 区分，
-- 不另建业务表——视频相对文档没有多出来的业务语义（元数据 file_object 全都有），
-- 与 DocumentFileService 当初"文档不建表"的取舍同一口径（见该 service 的类注释）。
--
-- 唯一要动的是 V106 那条约束：chk_file_object_private_document 只放行 document 进 PRIVATE 分区，
-- 私人视频（scope=PRIVATE, biz_type=video）会被它挡下。这里把它换成同时放行 document / video。
-- 图片行仍恒为 PUBLIC/0（FileFacadeImpl.upload 写死），这条约束对图片没有影响。
ALTER TABLE file_object DROP CHECK chk_file_object_private_document;
ALTER TABLE file_object
    ADD CONSTRAINT chk_file_object_private_partition CHECK (
        scope = 'PUBLIC' OR BINARY biz_type IN ('document', 'video')
    );
