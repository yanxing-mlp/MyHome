package com.familyhome.boot.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.boot.entity.OperationLogSettingDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作记录保留配置 Mapper。单行表（id=1），只走 BaseMapper 的 selectById/updateById。
 */
@Mapper
public interface OperationLogSettingMapper extends BaseMapper<OperationLogSettingDO> {
}
