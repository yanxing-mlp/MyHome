package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.entity.OperationLogDO;
import com.familyhome.boot.service.OperationLogService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.PageResult;
import com.familyhome.common.result.Result;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「操作记录」接口：超管查看用户操作审计（方案 v45）。
 *
 * <p>与系统日志/部署管理同一道门槛——{@link CurrentUserHolder#requirePermission(String)} 判 {@code oplog.access}：
 * 审计里有操作人、入参、响应摘要，只有勾了操作记录权限的角色（默认只有超管）能看。菜单只对有权限的人露出
 * 只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>只读审计流水，不在此处写审计（写入由 {@code OperationLogInterceptor} 在请求完成时异步落库）。
 * 保留天数走单行配置表 {@code operation_log_setting}，超管可在页面改（1..365，默认 7），过期行由定时任务清理。
 *
 * <p>这些接口本身（{@code /api/b/operation-logs*}）被审计层排除，避免「看审计」这个动作又生成审计记录的自我反馈。
 */
@RestController
@RequestMapping("/api/b/operation-logs")
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 分页查操作记录，按时间倒序。所有筛选维度都可选。
     *
     * @param source    B=管理端 / C=家庭端；缺省查全部
     * @param userId    操作人账号 id
     * @param success   1=成功 / 0=失败
     * @param startTime 起始时间（epoch 毫秒，含）
     * @param endTime   结束时间（epoch 毫秒，含）
     * @param keyword   模糊匹配 动作 / uri / 页面 / 操作人昵称
     */
    @GetMapping
    public Result<PageResult<OperationLogDO>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer success,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(required = false) String keyword) {
        CurrentUserHolder.requirePermission(PermissionCatalog.OPLOG_ACCESS);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int safePageNo = Math.max(pageNo, 1);
        return Result.ok(operationLogService.page(
                safePageNo, safePageSize, source, userId, success,
                toLocalDateTime(startTime), toLocalDateTime(endTime), keyword));
    }

    /** 读操作记录保留天数：{@code {retentionDays, available}}。 */
    @GetMapping("/retention")
    public Result<JsonNode> retention() {
        CurrentUserHolder.requirePermission(PermissionCatalog.OPLOG_ACCESS);
        return Result.ok(operationLogService.getRetention());
    }

    /** 设置操作记录保留天数（body {@code {"retentionDays": N}}，1..365），落库单行配置。 */
    @PutMapping("/retention")
    public Result<JsonNode> setRetention(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.OPLOG_ACCESS);
        return Result.ok(operationLogService.setRetention(body.path("retentionDays").asInt(-1)));
    }

    /** epoch 毫秒 → 系统时区 LocalDateTime；null 原样返回（表示该边界不设限）。 */
    private static LocalDateTime toLocalDateTime(Long epochMilli) {
        return epochMilli == null
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }
}
