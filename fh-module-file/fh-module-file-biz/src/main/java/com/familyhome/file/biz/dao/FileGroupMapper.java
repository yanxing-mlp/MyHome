package com.familyhome.file.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.file.biz.entity.FileGroupDO;
import org.apache.ibatis.annotations.Mapper;

/** 文件/视频分组 Mapper。 */
@Mapper
public interface FileGroupMapper extends BaseMapper<FileGroupDO> {
}
