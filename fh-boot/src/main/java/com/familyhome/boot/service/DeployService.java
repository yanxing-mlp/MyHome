package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 部署管理（离线部署包）：后端只做两件只读的事——报告「当前跑的是哪个产物版本」与回吐「部署历史」。
 *
 * <p><b>离线部署包不再有任何在线升级通道</b>：产物直跑的安装包里预置了 fat JAR + 两个前端 dist + schema 快照，
 * 部署即解包 {@code app/} 后 {@code java -jar}，全程不联网 clone / 构建。要把已部署的实例更新到新版本，
 * 由使用者<b>手动放置新的离线部署包并跑一遍部署脚本</b>——后端不下载、不换产物、不重启自己，也不触发任何部署进程。
 *
 * <p>运行版本戳读 {@code <FH_INSTALL_DIR>/app/version.json}（引擎冷启动/换包时解包落盘的产物清单，
 * 记录当前跑着的 payload 版本与两仓 commit）。开发机没有该目录时留空、{@link #isEnabled()} 为 false。
 *
 * <p>部署历史 {@code logs/deploy-history.json} 由外部部署脚本在跑到终态时写入，后端 {@link #getHistory()}
 * 只读回吐给超管查看，绝不自写。
 */
@Slf4j
@Service
public class DeployService {

    private final ObjectMapper objectMapper;
    private final String historyFile;
    /**
     * 安装根目录（引擎注入 {@code FH_INSTALL_DIR}，Spring 松弛绑定到属性 {@code fh.install-dir}——注意不是
     * {@code fh.deploy.install-dir}，那需要 {@code FH_DEPLOY_INSTALL_DIR}，引擎并不注入该名字）。
     * 运行版本戳读 {@code <install-dir>/app/version.json}——那是引擎冷启动/换包时解包并落盘的产物清单，
     * 记录当前跑着的 payload 版本与两仓 commit。开发机没有该目录时留空。
     */
    private final String installDir;

    public DeployService(
            ObjectMapper objectMapper,
            @Value("${fh.deploy.history-file:}") String historyFile,
            @Value("${fh.install-dir:}") String installDir) {
        this.objectMapper = objectMapper;
        this.historyFile = historyFile == null ? "" : historyFile.trim();
        this.installDir = installDir == null ? "" : installDir.trim();
    }

    /** 有安装根目录才算「产物直跑的部署机」；开发机 installDir 留空，无版本戳也无部署历史可报。 */
    public boolean isEnabled() {
        return !installDir.isEmpty();
    }

    /**
     * 部署管理能力探测：告诉前端「当前跑的是哪个产物版本、对应哪两个 commit」。
     *
     * <p>运行版本读 {@code <FH_INSTALL_DIR>/app/version.json}（引擎解包产物时落盘的版本戳），纯只读探测。
     * 拿不到运行版本（开发机无安装目录）只退化对应字段为空，绝不抛异常打挂部署管理页。
     */
    public ObjectNode capability() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("enabled", isEnabled());

        JsonNode running = readRunningVersion();
        String currentVersion = running == null ? "" : running.path("version").asText("");
        node.put("currentVersion", currentVersion);
        node.set("currentCommits", commitsOf(running));
        node.put("checkedAt", Instant.now().toString());
        return node;
    }

    /** 读运行版本戳 {@code <install-dir>/app/version.json}；开发机无安装目录或文件缺失/损坏都返回 null。 */
    private JsonNode readRunningVersion() {
        if (installDir.isEmpty()) {
            return null;
        }
        Path versionFile = Path.of(installDir, "app", "version.json");
        try {
            if (!Files.isRegularFile(versionFile)) {
                return null;
            }
            return objectMapper.readTree(Files.readString(versionFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取运行版本戳失败（忽略，按未知版本处理）: {}", e.toString());
            return null;
        }
    }

    /** 从 version.json 里抽出两仓 commit 组成 {@code {server, web}}；缺失的仓不写键。versionNode 为 null 时返回空对象。 */
    private ObjectNode commitsOf(JsonNode versionNode) {
        ObjectNode commits = objectMapper.createObjectNode();
        if (versionNode == null) {
            return commits;
        }
        String server = versionNode.path("server").path("commit").asText("");
        String web = versionNode.path("web").path("commit").asText("");
        if (!server.isEmpty()) {
            commits.put("server", server);
        }
        if (!web.isEmpty()) {
            commits.put("web", web);
        }
        return commits;
    }

    /**
     * 读部署历史：部署脚本每次跑到终态（done/failed）就把那一条完整状态追加进 {@code history-file}
     * （JSON 数组、新在前、上限 50）。本方法只读不写——<b>写历史的人是那个能活过后端之死的分离部署进程</b>。
     *
     * <p>未接入（history-file 留空）或文件还没有时返回空数组，而不是抛错：对超管来说「没有历史」和
     * 「历史文件还没生成」是同一件事，前端一律渲染空态即可。文件结构非法也退化成空数组，绝不让一个坏文件
     * 把整个部署管理页打挂。
     */
    public JsonNode getHistory() {
        if (historyFile.isEmpty()) {
            return objectMapper.createArrayNode();
        }
        try {
            Path file = Path.of(historyFile);
            if (!Files.isRegularFile(file)) {
                return objectMapper.createArrayNode();
            }
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            // 可能正撞上原子写的极短窗口，或历史文件被写坏；退化成空数组，下次再读。
            log.debug("读取部署历史文件失败（忽略，按空历史处理）: {}", e.toString());
            return objectMapper.createArrayNode();
        }
    }
}
