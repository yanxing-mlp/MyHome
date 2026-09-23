package com.familyhome.recipe.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.familyhome.recipe.biz.entity.RecipePracticeOptionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecipePracticeOptionMapper extends BaseMapper<RecipePracticeOptionDO> {

    /** 按选项名称的数据库排序规则比较，防止临时占位名占用本次提交的最终名称。names 必须非空。 */
    @Select({
            "<script>",
            "SELECT CONVERT(#{name} USING utf8mb4) COLLATE utf8mb4_0900_ai_ci IN",
            "<foreach collection='names' item='item' open='(' separator=',' close=')'>",
            "CONVERT(#{item} USING utf8mb4) COLLATE utf8mb4_0900_ai_ci",
            "</foreach>",
            "</script>"
    })
    boolean matchesAnyName(@Param("name") String name, @Param("names") List<String> names);
}
