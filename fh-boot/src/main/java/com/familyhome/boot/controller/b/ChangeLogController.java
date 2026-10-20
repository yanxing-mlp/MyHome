package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.ChangeLogService;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更记录：前后端两仓当前分支的提交历史。
 *
 * <p><b>数据从哪来</b>：由 {@link ChangeLogService} 决定——**实时优先**从 GitHub 公开 API 拉两仓
 * 当前分支最近 100 条 commit（两仓都是 public、无需 token），**拉不到就回退**构建期
 * {@code scripts/gen-change-log.py} 烘进 classpath 的 {@code git/change-log.json} 快照（离线机没外网时的兜底）。
 * 响应里每仓带一个 {@code source}（live / snapshot / unavailable）、顶层带一个汇总 {@code source}，前端据此措辞。
 *
 * <p><b>为什么走后端而不是把 commit 直接塞进前端 bundle</b>：整份提交历史属于"敏感技术信息"，
 * 放进公开的 JS 里任何人都能扒出来。挂在 {@code /api/b} 下、每次 {@link CurrentUserHolder#requireAdmin()}，
 * 与账号管理 / 目录管理同一道门槛——菜单只对 ADMIN 露出只是体验，绕开 UI 直接敲接口照样 403。
 */
@RestController
@RequestMapping("/api/b")
public class ChangeLogController {

    private final ChangeLogService changeLogService;

    public ChangeLogController(ChangeLogService changeLogService) {
        this.changeLogService = changeLogService;
    }

    @GetMapping("/change-log")
    public Result<JsonNode> changeLog() {
        CurrentUserHolder.requireAdmin();
        return Result.ok(changeLogService.getChangeLog());
    }
}
