package com.familyhome.boot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一条用户操作审计记录，表 {@code operation_log}（方案 v45，system 域，fh-boot 直接持有）。
 *
 * <p>读写全记：{@link #httpMethod} 为 GET/POST/PUT/DELETE 的任何一次业务请求都由采集层
 * （{@code OperationLogInterceptor}）在请求完成后落一行。落库走异步单线程 executor，
 * 不阻塞业务响应；采集本身对业务只读，绝不因审计失败而让请求失败。
 *
 * <p>刻意<b>没有</b> {@code @TableLogic}：审计行是既成事实的流水，不做软删；
 * 过期靠保留天数由 {@code @Scheduled} 每日物理删除（见 {@code OperationLogService}）。
 * create_time/update_time 交给 DB 列默认值，实体上不写 {@code @TableField(fill=...)}
 * （本仓没注册 MetaObjectHandler，见项目记忆）。
 */
@Getter
@Setter
@TableName("operation_log")
public class OperationLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源端：B=管理端 / C=家庭端，由 uri 前缀（/api/b|/api/c）判定 */
    private String source;

    /** 操作人账号 id；未登录（如登录接口本身）为 null */
    private Long userId;

    /** 操作人昵称，落库即快照，账号改名/删除后历史仍可读 */
    private String userName;

    /** 操作人角色（ADMIN/MEMBER），同为快照 */
    private String userRole;

    /** 前端 {@code X-Op-Page} 头带的中文页面名；缺省为 null */
    private String page;

    /** 中文动作，由后端动作字典按 METHOD+uri 翻译（见 OperationLogService） */
    private String action;

    private String httpMethod;

    private String uri;

    /** 请求出入参：入参（query/body）+ 响应摘要，敏感字段脱敏后落库 */
    private String requestParams;

    private String responseSummary;

    /** 业务是否成功：HTTP 2xx 且 Result.code=0 */
    private Integer success;

    /** 失败时的业务错误码 */
    private String errorCode;

    /** 处理耗时（毫秒） */
    private Integer durationMs;

    private String clientIp;

    /**
     * 请求链路 id（方案 v46），由 {@code TraceIdFilter} 每请求生成，与该请求的日志行、响应头 {@code X-Trace-Id} 同值。
     * 用途是「日志回溯」：从一条审计行拿到它，就能在系统日志里定位这次请求期间的全部日志。
     * V600 之前落库的历史行为 null（那时还没这套机制）。
     */
    private String traceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
