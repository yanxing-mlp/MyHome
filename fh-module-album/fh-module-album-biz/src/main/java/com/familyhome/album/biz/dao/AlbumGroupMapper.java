package com.familyhome.album.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.album.biz.entity.AlbumGroupDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlbumGroupMapper extends BaseMapper<AlbumGroupDO> {

    /** 必须在事务内调用，且先于文件锁；锁后由访问服务校验分区和状态。 */
    @Select("SELECT * FROM album_group WHERE id = #{id} FOR UPDATE")
    AlbumGroupDO selectByIdForUpdate(@Param("id") Long id);
}
