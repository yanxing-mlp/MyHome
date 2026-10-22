package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.DeployService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部署管理接口（离线部署包）：只读地报告当前运行版本与部署历史。
 *
 * <p>与变更记录同一道门槛——{@link CurrentUserHolder#requirePermission(String)} 判 {@code deploy.access}：
 * 部署信息只对勾了部署管理权限的角色（默认只有超管）露出。菜单只对有权限的人露出只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p><b>离线部署包没有任何在线升级触发接口</b>：更新由使用者手动放置新的离线部署包并跑部署脚本完成，
 * 后端不下载、不换产物、不重启自己。这里只剩 {@code /capability}（当前版本）与 {@code /history}（部署历史）两个只读接口。
 */
@RestController
@RequestMapping("/api/b/deploy")
public class DeployController {

    private final DeployService deployService;

    public DeployController(DeployService deployService) {
        this.deployService = deployService;
    }

    /**
     * 部署管理能力探测：是否接入产物直跑的部署机 + 当前运行的产物版本戳与两仓 commit。
     * 前端据此渲染「当前版本」卡片。
     */
    @GetMapping("/capability")
    public Result<JsonNode> capability() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        return Result.ok(deployService.capability());
    }

    /**
     * 读取部署历史（历次部署的终态记录，新在前，最多 50 条）。同一道 ADMIN 门槛——
     * 历史里带着每次部署的 commit 迁移和完整日志，只对超管露出。未接入部署机或还没有历史时返回空数组。
     */
    @GetMapping("/history")
    public Result<JsonNode> history() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DEPLOY_ACCESS);
        return Result.ok(deployService.getHistory());
    }
}
