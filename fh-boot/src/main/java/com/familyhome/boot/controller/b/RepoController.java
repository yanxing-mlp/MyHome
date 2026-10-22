package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.CodeRepoService;
import com.familyhome.boot.service.RepoService;
import com.familyhome.boot.service.RepoService.RepoHandle;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「代码仓库」接口：在线浏览 / 编辑后端或前端源码 git 仓库里的文本文件，并一键推送到远端（方案 v51）。
 *
 * <p>与知识库（{@link KnowledgeController}）同一套引擎、同一批端点，只多一个 {@code repo} 参数区分是
 * {@code server}（后端源码仓）还是 {@code web}（前端源码仓）。<b>门禁按仓库分别判</b>：{@code server} 判
 * {@code server-repo.access}、{@code web} 判 {@code web-repo.access}——能改源码还能推到远端是高危操作，
 * 默认只有勾了对应权限的角色（默认只有超管）能进。藏菜单只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>五个端点（每个都要 {@code repo}）：
 * <ul>
 *   <li>{@code GET  /tree?repo=}——文件树（排除 .git，二进制标 editable=false）；未配置目录时回 available=false。</li>
 *   <li>{@code GET  /file?repo=&path=}——读一个文本文件全文。</li>
 *   <li>{@code PUT  /file}——把内容写回本地工作副本（保存，不碰 git）。body {@code {repo, path, content}}。</li>
 *   <li>{@code GET  /git/status?repo=}——分支 + 待提交改动清单，供「推送前预览」。</li>
 *   <li>{@code POST /git/push}——{@code git add -A + commit -m <message> + push} 一次做完。body {@code {repo, message}}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/b/repo")
public class RepoController {

    private final CodeRepoService codeRepoService;
    private final RepoService repoService;

    public RepoController(CodeRepoService codeRepoService, RepoService repoService) {
        this.codeRepoService = codeRepoService;
        this.repoService = repoService;
    }

    /**
     * 先按仓库键判对应权限码，再解析句柄——两件事绑在一起，任何"要动手"的端点都先过这一关。
     * 未知 repo 或无权限都在拿到句柄之前就被挡下（无权限 403、未知键 400）。
     */
    private RepoHandle authorize(String repo) {
        String code = switch (repo == null ? "" : repo) {
            case CodeRepoService.REPO_SERVER -> PermissionCatalog.SERVER_REPO_ACCESS;
            case CodeRepoService.REPO_WEB -> PermissionCatalog.WEB_REPO_ACCESS;
            default -> throw BizException.of(ErrorCode.BAD_REQUEST, "未知的代码仓库：" + repo);
        };
        CurrentUserHolder.requirePermission(code);
        return codeRepoService.handle(repo);
    }

    /** 文件树。未接入（目录留空）时返回 {@code {available:false, tree:[]}}，前端显示空态。 */
    @GetMapping("/tree")
    public Result<JsonNode> tree(@RequestParam String repo) {
        RepoHandle handle = authorize(repo);
        return Result.ok(repoService.getTree(handle));
    }

    /** 读取一个文本文件的完整内容。{@code path} 是相对仓库根、以 {@code /} 分隔的路径。 */
    @GetMapping("/file")
    public Result<JsonNode> readFile(@RequestParam String repo, @RequestParam String path) {
        RepoHandle handle = authorize(repo);
        return Result.ok(repoService.readFile(handle, path));
    }

    /** 保存：把内容写回本地工作副本（不推送）。body {@code {"repo": "...", "path": "...", "content": "..."}}。 */
    @PutMapping("/file")
    public Result<JsonNode> writeFile(@RequestBody JsonNode body) {
        RepoHandle handle = authorize(body.path("repo").asText(""));
        return Result.ok(repoService.writeFile(
                handle,
                body.path("path").asText(""),
                body.path("content").asText("")));
    }

    /** git 工作副本状态：分支 / 待提交改动清单 / 是否干净 / 领先上游几个提交。 */
    @GetMapping("/git/status")
    public Result<JsonNode> gitStatus(@RequestParam String repo) {
        RepoHandle handle = authorize(repo);
        return Result.ok(repoService.gitStatus(handle));
    }

    /** 一键推送：{@code git add -A + commit -m <message> + push}。body {@code {"repo": "...", "message": "..."}}（message 可空）。 */
    @PostMapping("/git/push")
    public Result<JsonNode> push(@RequestBody JsonNode body) {
        RepoHandle handle = authorize(body.path("repo").asText(""));
        String message = body.path("message").asText("");
        return Result.ok(repoService.push(handle, message));
    }
}
