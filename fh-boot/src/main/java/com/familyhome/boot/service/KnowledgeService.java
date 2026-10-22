package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.boot.service.RepoService.RepoHandle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 「知识库」：在线浏览 / 编辑一个 git 仓库里的文本文件，并一键推送到远端（方案 v49）。
 *
 * <p><b>本类只是 {@link RepoService} 的一层薄封装</b>：知识库与后端代码仓库、前端代码仓库（方案 v51）用的是同一套
 * 「列文件树 / 读写文本 / 跑 git」引擎，差异只在于指向哪个目录、默认提交信息叫什么。所以这里只负责把
 * {@code fh.knowledge.*} 配置解析成一个 {@link RepoHandle} 交给 {@link RepoService}，公开方法签名与 v49 逐字一致
 * （{@link KnowledgeController} 与前端 {@code api/knowledge.ts} 因此一行都不用改）。
 *
 * <p>内容根目录 = 一个已有的 git 工作副本（部署机上是 {@code MyHome--Knowledge} 仓库的 clone），由
 * {@code fh.knowledge.repo-dir} 指定；留空即视为「当前环境未接入知识库」，文件树接口回 {@code available=false}、
 * 读/写/推送接口 400 拒绝（与系统日志页 dev 空态同一手法）。远端是 SSH 地址时用可选的
 * {@code fh.knowledge.git-ssh-command} 注入 {@code GIT_SSH_COMMAND} 指定私钥（个人 GitHub 走独立 key）。
 *
 * <p>安全边界（路径穿越防护、.git 排除、文本白名单、2MB 上限、进程内跑 git 的理由）全部在 {@link RepoService} 里，
 * 本类不重复。
 */
@Service
public class KnowledgeService {

    private static final String LABEL = "知识库";
    private static final String DEFAULT_COMMIT_MSG = "docs: 通过知识库在线编辑更新";

    private final RepoService repoService;
    private final String repoDirConfig;
    private final String gitSshCommand;

    public KnowledgeService(
            RepoService repoService,
            @Value("${fh.knowledge.repo-dir:}") String repoDirConfig,
            @Value("${fh.knowledge.git-ssh-command:}") String gitSshCommand) {
        this.repoService = repoService;
        this.repoDirConfig = repoDirConfig;
        this.gitSshCommand = gitSshCommand;
    }

    /** 每次调用现解析句柄：配置在启动时注入、运行期不变，但目录可能被运维挂载/移除，故按需重解析。 */
    private RepoHandle handle() {
        return RepoService.handle(repoDirConfig, gitSshCommand, LABEL, DEFAULT_COMMIT_MSG);
    }

    /** 本运行环境是否接入了知识库（repo-dir 已配置且目录存在）。 */
    public boolean available() {
        return repoService.available(handle());
    }

    /** 递归列出知识库目录下的文件树（排除 .git）。未配置时 {@code available=false, tree=[]}。 */
    public ObjectNode getTree() {
        return repoService.getTree(handle());
    }

    /** 读取一个文本文件的完整内容。 */
    public ObjectNode readFile(String relPath) {
        return repoService.readFile(handle(), relPath);
    }

    /** 把内容整体写回本地工作副本（保存，不碰 git）。 */
    public ObjectNode writeFile(String relPath, String content) {
        return repoService.writeFile(handle(), relPath, content);
    }

    /** git 工作副本状态：分支 / 待提交改动清单 / 是否干净 / 领先上游几个提交。 */
    public ObjectNode gitStatus() {
        return repoService.gitStatus(handle());
    }

    /** 一键推送：{@code git add -A + commit -m <message> + push}。 */
    public ObjectNode push(String message) {
        return repoService.push(handle(), message);
    }
}
