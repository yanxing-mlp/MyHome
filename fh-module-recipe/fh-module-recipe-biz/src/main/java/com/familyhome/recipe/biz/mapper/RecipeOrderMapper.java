package com.familyhome.recipe.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.recipe.biz.entity.RecipeOrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 点餐订单 Mapper。
 */
@Mapper
public interface RecipeOrderMapper extends BaseMapper<RecipeOrderDO> {

    /** append 在持有车锁后锁主单，与完成 / 取消 / 删除的主单写锁串行。 */
    @Select("SELECT * FROM recipe_order WHERE id = #{id} FOR UPDATE")
    RecipeOrderDO selectByIdForUpdate(@Param("id") Long id);
}
