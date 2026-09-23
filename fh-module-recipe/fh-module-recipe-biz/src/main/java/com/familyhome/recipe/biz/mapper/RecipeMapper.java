package com.familyhome.recipe.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.recipe.biz.entity.RecipeDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RecipeMapper extends BaseMapper<RecipeDO> {
}
