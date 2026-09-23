package com.familyhome.album.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.album.biz.entity.AlbumImageGroupRelDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 相册图片-分组关联 Mapper。
 */
@Mapper
public interface AlbumImageGroupRelMapper extends BaseMapper<AlbumImageGroupRelDO> {
}
