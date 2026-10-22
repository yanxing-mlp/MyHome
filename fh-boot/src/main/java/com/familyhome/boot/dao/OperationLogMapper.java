package com.familyhome.boot.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.boot.entity.OperationLogDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 操作审计流水 Mapper。
 *
 * <p>分页列表查询由服务层用 {@code LambdaQueryWrapper} + 分页插件表达，不必写 SQL；
 * 这里只有一条自定义语句：按保留天数删过期行（{@code @Scheduled} 每日调用）。
 * 审计行是既成事实的流水、不做软删，所以是物理 {@code DELETE}。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogDO> {

    /**
     * 删除 {@code createTime} 早于给定时刻的审计行。
     *
     * @param before 保留窗口左界（now - retentionDays）
     * @return 删除行数
     */
    @Delete("DELETE FROM operation_log WHERE create_time < #{before}")
    int deleteOlderThan(@Param("before") java.time.LocalDateTime before);
}
