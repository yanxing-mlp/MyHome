package com.familyhome.file.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件/视频分组，表 {@code file_group}。
 *
 * <p>一张表用 {@code bizType}（{@code document} / {@code video}）区分「文件分组」与「视频分组」两类，
 * 与 {@code file_object} 同域、同分区口径（{@link DataScope} PUBLIC/0 全家共享、PRIVATE/当前账号隔离）。
 *
 * <p><b>硬删除</b>（与 {@code file_category} 字典一致，不用 {@code file_object} 那种两态 {@code deleted}）：
 * 删分组时靠 {@code file_group_rel.group_id} 外键级联清掉关联行，不留相册 V205 那种孤儿关联。
 * 名称唯一性范围 = 同 bizType + 同分区（{@code uk_file_group_name}，含 TRIM 生成列），删除后同名可重建。
 *
 * <p>{@code unique_name} 是数据库生成列，刻意不映射到实体——STORED 生成列不能被 INSERT，
 * 与 {@link FileCategoryDO} 省略 {@code unique_name} 同一处理。
 */
@Getter
@Setter
@TableName("file_group")
public class FileGroupDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组所属业务：{@code document} 文件 / {@code video} 视频 */
    private String bizType;

    /** 分组名 */
    private String name;

    /** 排序值，越大越靠前；新建取 {@code max(sort)+1}，拖拽后整批重排 */
    private Integer sort;

    private DataScope scope;

    /** PUBLIC 为 0；PRIVATE 为所属账号 */
    private Long ownerId;

    /** 添加人（{@code app_user.id}）；PRIVATE 行里它的含义是属主 */
    private Long creatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
