package com.familyhome.file.biz.model.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件分类视图（下拉框选项）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileCategoryVO {

    private Long id;

    private String name;
}
