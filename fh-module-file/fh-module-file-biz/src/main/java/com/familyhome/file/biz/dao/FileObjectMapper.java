package com.familyhome.file.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.file.biz.entity.FileObjectDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileObjectMapper extends BaseMapper<FileObjectDO> {

    /**
     * 根据 md5 查未删除的文件记录（秒传判定用）。
     *
     * @return 命中时返回第一条；未命中返回 null
     */
    @Select("SELECT * FROM file_object WHERE md5 = #{md5} AND deleted = 0 "
            + "AND scope = 'PUBLIC' AND owner_id = 0 LIMIT 1")
    FileObjectDO selectByMd5(@Param("md5") String md5);

    /** 私人秒传只允许复用同一账号的私人文档，不能跨账号或从图片/公共文档取候选。 */
    @Select("SELECT * FROM file_object WHERE md5 = #{md5} AND deleted = 0 "
            + "AND scope = 'PRIVATE' AND owner_id = #{ownerId} AND biz_type = 'document' LIMIT 1")
    FileObjectDO selectPrivateDocumentByMd5(@Param("md5") String md5, @Param("ownerId") Long ownerId);

    /** 只锁本域行，包含软删记录；调用方须在同一事务中持锁并重读业务引用。 */
    @Select({"<script>",
            "SELECT id FROM file_object WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY id FOR UPDATE",
            "</script>"})
    List<Long> lockByIds(@Param("ids") List<Long> ids);
}
