package com.familyhome.boot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作记录保留配置，表 {@code operation_log_setting}（方案 v45）。
 *
 * <p>单行表：id 恒为 1（{@link IdType#INPUT}，不由 DB 自增），种子在 V600 里插入。
 * 与 {@code recipe_cart_state} 的固定 id 同一手法——"全局只有一份"的东西用一行装，
 * 省掉按业务键查的分支。改保留天数就是 {@code updateById} 这一行。
 */
@Getter
@Setter
@TableName("operation_log_setting")
public class OperationLogSettingDO {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 保留天数，1..365，默认 7；超过窗口的审计行由 @Scheduled 每日物理删除 */
    private Integer retentionDays;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
