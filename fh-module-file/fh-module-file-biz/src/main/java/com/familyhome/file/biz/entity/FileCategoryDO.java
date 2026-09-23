package com.familyhome.file.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件分类字典，表 {@code file_category}。
 *
 * <p>按文档分区隔离，同一 scope/ownerId 下 TRIM(name) 唯一；没有软删除列。
 * 一期只在 B 端"文件管理"的下拉框里消费（含内联新建），没有独立的管理页，所以也没有改名和排序接口。
 */
@Getter
@Setter
@TableName("file_category")
public class FileCategoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名：文档 / 数据 / 其他 */
    private String name;

    private DataScope scope;

    /** 公共分类为 0；私人分类为所属账号 */
    private Long ownerId;

    private LocalDateTime createTime;

    /** 修改时间 */
    private LocalDateTime updateTime;
}
