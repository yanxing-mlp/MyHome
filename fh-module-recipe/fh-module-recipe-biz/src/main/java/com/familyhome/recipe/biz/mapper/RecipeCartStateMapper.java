package com.familyhome.recipe.biz.mapper;

import com.familyhome.recipe.biz.entity.RecipeCartCheckoutDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 共享购物车的持久行锁与消费回执；不提供删除回执的方法。
 */
@Mapper
public interface RecipeCartStateMapper {

    @Select("SELECT version FROM recipe_cart_state WHERE id = 1 FOR UPDATE")
    Long lockVersion();

    @Update("UPDATE recipe_cart_state SET version = version + 1 WHERE id = 1 AND version = #{version}")
    int advance(@Param("version") Long version);

    // 当前读，不在等待订单行锁之前建立 RR 一致性快照。
    @Select("SELECT cart_version, order_id, target_order_id, create_time, update_time"
            + " FROM recipe_cart_checkout WHERE cart_version = #{version} FOR UPDATE")
    RecipeCartCheckoutDO selectCheckout(@Param("version") Long version);

    @Insert("INSERT INTO recipe_cart_checkout (cart_version, order_id, target_order_id)"
            + " VALUES (#{version}, #{orderId}, #{targetOrderId})")
    int insertCheckout(@Param("version") Long version, @Param("orderId") Long orderId,
                       @Param("targetOrderId") Long targetOrderId);
}
