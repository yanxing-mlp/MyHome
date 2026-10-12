package com.familyhome.file.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.file.biz.entity.FileGroupRelDO;
import org.apache.ibatis.annotations.Mapper;

/** 文件/视频-分组关联 Mapper。 */
@Mapper
public interface FileGroupRelMapper extends BaseMapper<FileGroupRelDO> {
}
