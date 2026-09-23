package com.familyhome.recipe.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.recipe.biz.entity.RecipeOrderItemDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细 Mapper。
 */
@Mapper
public interface RecipeOrderItemMapper extends BaseMapper<RecipeOrderItemDO> {
}
