package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.boot.service.DeployService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「部署最新」接口：变更记录页在线更新入口。
 *
 * <p>与变更记录同一道门槛——{@link CurrentUserHolder#requirePermission(String)} 判 {@code deploy.access}：
 * 下载替换产物 + 重启整套服务是最高危操作，只有勾了部署管理权限的角色（默认只有超管）能点。菜单只对有权限的人
 * 露出只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>触发接口只负责点火并立刻返回 runId（后端随后会被重启杀掉），进度靠 {@code GET /deploy/progress} 轮询，
 * 由 {@link DeployService} 读部署脚本写出的进度文件——重启前后读的是同一个文件，故进度能无缝接上。
 */
@RestController
@RequestMapping("/api/b/deploy")
public class DeployController {

    private final DeployService deployService;
    private final ObjectMapper objectMapper;

    public DeployController(DeployService deployService, ObjectMapper objectMapper) {
        this.deployService = deployService;
        this.objectMapper = objectMapper;
    }

    /**
     * 部署管理能力探测：是否接入「一键部署」+ 当前运行版本 + GitHub Release 最新版本 + 是否有更新可部署。
     * 前端据此决定要不要显示「部署最新」入口、以及版本对比提示，避免点了才报错。
     */
    @GetMapping("/capability")
    public Result<JsonNode> capability() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        return Result.ok(deployService.capability());
    }

    /**
     * 触发一次「部署最新」：分离拉起引擎进程，从 GitHub Release 下载最新产物包、校验哈希、原子替换本地 app 目录并重启后端。
     * 只负责点火并立即返回本次部署的 runId（后端随后会被重启杀掉），进度靠 {@code GET /deploy/progress} 轮询。
     */
    @PostMapping("/latest")
    public Result<JsonNode> deployLatest() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        String runId = deployService.triggerLatest();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("runId", runId);
        return Result.ok(node);
    }

    /** 读取部署进度（最近一次部署的完整状态）。runId 仅供前端关联自己那次，可缺省。 */
    @GetMapping("/progress")
    public Result<JsonNode> progress(@RequestParam(required = false) String runId) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        return Result.ok(deployService.getProgress(runId));
    }

    /**
     * 读取部署历史（历次部署的终态记录，新在前，最多 50 条）。同变更记录/进度一道 ADMIN 门槛——
     * 历史里带着每次部署的 commit 迁移和完整日志，只对超管露出。未接入一键部署或还没有历史时返回空数组。
     */
    @GetMapping("/history")
    public Result<JsonNode> history() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        return Result.ok(deployService.getHistory());
    }
}
