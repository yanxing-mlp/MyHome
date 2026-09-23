package com.familyhome.album.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.familyhome.common.enums.AlbumScope;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相册城市统计表（方案 §6.5）。
 * 
 * <p>按 scope/owner_id/city 唯一，存储当前分区各城市在架图片数，归零后移除。
 */
@Data
@TableName("album_city")
public class AlbumCityDO {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 与图片一致的数据分区 */
    private AlbumScope scope;

    /** FAMILY 恒为 0；PERSONAL 为所属账号 */
    private Long ownerId;

    /**
     * 城市名称
     */
    private String city;

    /**
     * 该城市的图片数量
     */
    private Integer count;

    /**
     * 创建时间
     *
     * <p>不写 {@code @TableField(fill = ...)}}：全工程没有注册 {@code MetaObjectHandler}，而带 fill 的字段
     * MyBatis-Plus 会<b>无条件</b>放进 insert 的列表里，值又是 null，于是撞 {@code create_time NOT NULL}。
     * 本仓库其余 DO 都一样：留 null 让列上的 {@code DEFAULT CURRENT_TIMESTAMP} 兜住。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
