package com.familyhome.album.api.dto;

import lombok.Data;

/**
 * 城市统计 DTO。
 * 
 * <p>用于返回城市名称及其对应的图片数量。
 */
@Data
public class AlbumCityDTO {
    /** 城市名称 */
    private String city;
    
    /** 图片数量 */
    private Long count;
}
