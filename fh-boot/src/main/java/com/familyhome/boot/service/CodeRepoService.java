package com.familyhome.boot.service;

import com.familyhome.boot.service.RepoService.RepoHandle;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 「代码仓库」配置解析：把两个源码 git 工作副本（后端 / 前端）的目录与 SSH 私钥配置，按仓库键解析成
 * {@link RepoHandle} 交给 {@link RepoService}（方案 v51）。
 *
 * <p>与 {@link KnowledgeService} 完全同构——都是「一份配置 + 一个句柄 + 委托通用引擎」，区别只在于这里有
 * <b>两个</b>仓库、用一个 {@code repo} 键（{@code server} / {@code web}）区分，各自独立配置目录、独立权限码门禁
 * （{@code server-repo.access} / {@code web-repo.access}，在 {@link com.familyhome.boot.controller.b.RepoController} 里判）。
 *
 * <p>部署机上这两个目录通常就是应用自身源码的 clone（{@code MyHome} / {@code MyHome-React}）；开发机默认留空 →
 * 句柄 dir=null → 文件树接口回 {@code available=false}、前端空态，读/写/推送 400 拒绝（优雅退化，与知识库同一口径）。
 */
@Service
public class CodeRepoService {

    /** 仓库键：后端源码仓（GitHub {@code MyHome}）。 */
    public static final String REPO_SERVER = "server";
    /** 仓库键：前端源码仓（GitHub {@code MyHome-React}）。 */
    public static final String REPO_WEB = "web";

    private static final String DEFAULT_COMMIT_MSG = "chore: 通过后台在线编辑更新代码";

    private final String serverDir;
    private final String serverSsh;
    private final String webDir;
    private final String webSsh;

    public CodeRepoService(
            @Value("${fh.repo.server.dir:}") String serverDir,
            @Value("${fh.repo.server.git-ssh-command:}") String serverSsh,
            @Value("${fh.repo.web.dir:}") String webDir,
            @Value("${fh.repo.web.git-ssh-command:}") String webSsh) {
        this.serverDir = serverDir;
        this.serverSsh = serverSsh;
        this.webDir = webDir;
        this.webSsh = webSsh;
    }

    /**
     * 按仓库键解析句柄。{@code server} → 后端代码仓库，{@code web} → 前端代码仓库；其余一律 400（防脏键）。
     * 每次现解析：配置运行期不变，但目录可能被运维挂载/移除。
     */
    public RepoHandle handle(String repo) {
        if (REPO_SERVER.equals(repo)) {
            return RepoService.handle(serverDir, serverSsh, "后端代码仓库", DEFAULT_COMMIT_MSG);
        }
        if (REPO_WEB.equals(repo)) {
            return RepoService.handle(webDir, webSsh, "前端代码仓库", DEFAULT_COMMIT_MSG);
        }
        throw BizException.of(ErrorCode.BAD_REQUEST, "未知的代码仓库：" + repo);
    }
}
