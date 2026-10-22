package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.KnowledgeService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「知识库」接口：在线浏览 / 编辑一个 git 仓库里的文本文件，并一键推送到远端（方案 v49）。
 *
 * <p>与系统日志 / 部署管理同一道门槛——{@link CurrentUserHolder#requirePermission(String)} 判
 * {@code knowledge.access}：知识库能改文件、还能把改动推到远端仓库，是高危操作，默认只有勾了知识库权限的角色
 * （默认只有超管）能进。菜单只对有权限的人露出只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>五个只读 / 写接口：
 * <ul>
 *   <li>{@code GET  /tree}——文件树（排除 .git，二进制标 editable=false）；未配置 repo-dir 时回 available=false。</li>
 *   <li>{@code GET  /file?path=}——读一个文本文件全文。</li>
 *   <li>{@code PUT  /file}——把内容写回本地工作副本（保存，不碰 git）。body {@code {path, content}}。</li>
 *   <li>{@code GET  /git/status}——分支 + 待提交改动清单，供「推送前预览」。</li>
 *   <li>{@code POST /git/push}——{@code git add -A + commit -m <message> + push} 一次做完。body {@code {message}}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/b/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 文件树。未接入知识库（repo-dir 留空）时返回 {@code {available:false, tree:[]}}，前端显示空态。 */
    @GetMapping("/tree")
    public Result<JsonNode> tree() {
        CurrentUserHolder.requirePermission(PermissionCatalog.KNOWLEDGE_ACCESS);
        return Result.ok(knowledgeService.getTree());
    }

    /** 读取一个文本文件的完整内容。{@code path} 是相对仓库根、以 {@code /} 分隔的路径。 */
    @GetMapping("/file")
    public Result<JsonNode> readFile(@RequestParam String path) {
        CurrentUserHolder.requirePermission(PermissionCatalog.KNOWLEDGE_ACCESS);
        return Result.ok(knowledgeService.readFile(path));
    }

    /** 保存：把内容写回本地工作副本（不推送）。body {@code {"path": "...", "content": "..."}}。 */
    @PutMapping("/file")
    public Result<JsonNode> writeFile(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.KNOWLEDGE_ACCESS);
        return Result.ok(knowledgeService.writeFile(
                body.path("path").asText(""),
                body.path("content").asText("")));
    }

    /** git 工作副本状态：分支 / 待提交改动清单 / 是否干净 / 领先上游几个提交。 */
    @GetMapping("/git/status")
    public Result<JsonNode> gitStatus() {
        CurrentUserHolder.requirePermission(PermissionCatalog.KNOWLEDGE_ACCESS);
        return Result.ok(knowledgeService.gitStatus());
    }

    /** 一键推送：{@code git add -A + commit -m <message> + push}。body {@code {"message": "..."}}（可空，用默认提交信息）。 */
    @PostMapping("/git/push")
    public Result<JsonNode> push(@RequestBody(required = false) JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.KNOWLEDGE_ACCESS);
        String message = body == null ? "" : body.path("message").asText("");
        return Result.ok(knowledgeService.push(message));
    }
}
