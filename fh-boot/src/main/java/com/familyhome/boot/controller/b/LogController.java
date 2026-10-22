package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.LogService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「系统日志」接口：后台实时查看日志文件（方案 v43）。
 *
 * <p>与部署管理同一道门槛——{@link CurrentUserHolder#requirePermission(String)} 判 {@code logs.access}：
 * 日志里可能带堆栈、路径、部署细节，只有勾了系统日志权限的角色（默认只有超管）能看。菜单只对有权限的人露出
 * 只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>只读日志内容、绝不写日志文件：{@code GET /files} 列日志目录下的 {@code *.log}，{@code GET /tail} 从上次 offset
 * 增量读新行。前端 ~1.5s 轮询 tail 实现「实时」（与部署进度同一机制，全仓无 SSE/WebSocket）。
 *
 * <p>此外挂一对「日志保留天数」设置接口（{@code GET/PUT /retention}，方案 v44）：把 logback 的
 * {@code rollingpolicy.max-history} 做成超管可在页面上改的设置，默认 7 天，改动持久化并立即生效。
 */
@RestController
@RequestMapping("/api/b/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    /** 列出可查看的日志文件（按最后修改时间倒序）。开发机未落日志文件时返回空数组。 */
    @GetMapping("/files")
    public Result<JsonNode> files() {
        CurrentUserHolder.requirePermission(PermissionCatalog.LOGS_ACCESS);
        return Result.ok(logService.listFiles());
    }

    /**
     * 从 {@code offset} 字节处增量读取指定日志文件的新行。{@code offset} 缺省（-1）表示初次打开，
     * 由服务端回溯读末尾一块；返回值里的 {@code offset} 是下一次轮询要传回来的游标。
     */
    @GetMapping("/tail")
    public Result<JsonNode> tail(
            @RequestParam String file,
            @RequestParam(required = false, defaultValue = "-1") long offset) {
        CurrentUserHolder.requirePermission(PermissionCatalog.LOGS_ACCESS);
        return Result.ok(logService.tail(file, offset));
    }

    /** 读日志保留天数设置：{@code {retentionDays, available}}。开发机无日志目录时 available=false、天数回默认 7。 */
    @GetMapping("/retention")
    public Result<JsonNode> retention() {
        CurrentUserHolder.requirePermission(PermissionCatalog.LOGS_ACCESS);
        return Result.ok(logService.getRetention());
    }

    /** 设置日志保留天数（body {@code {"retentionDays": N}}，1..365），持久化并立即应用到 logback。 */
    @PutMapping("/retention")
    public Result<JsonNode> setRetention(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.LOGS_ACCESS);
        return Result.ok(logService.setRetention(body.path("retentionDays").asInt(-1)));
    }
}
