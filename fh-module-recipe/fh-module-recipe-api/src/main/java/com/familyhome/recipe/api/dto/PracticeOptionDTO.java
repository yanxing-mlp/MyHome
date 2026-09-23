package com.familyhome.recipe.api.dto;

import lombok.Data;

/**
 * 做法选项 DTO（组内一项：不辣/微辣/好辣）。按录入顺序（id 升序）返回。
 */
@Data
public class PracticeOptionDTO {

    private Long id;

    private Long groupId;

    private String name;
}
