package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 「部署最新」：部署管理页点一下，就让引擎进程从 GitHub Release 拉最新产物包、原子换掉本地 app 目录并重启后端。
 *
 * <p><b>产物直跑而非 git pull</b>：运行态只有「后端 JAR + 两个前端 dist + MySQL」，更新走 GitHub Release
 * 下载校验哈希后替换，不再联网 clone 源码或现场 mvn/pnpm 构建。运行版本戳读 {@code <FH_INSTALL_DIR>/app/version.json}，
 * 最新版本戳打 {@code https://github.com/<FH_DEPLOY_RELEASE_REPO>/releases/latest/download/version.json}，
 * 两者对比即 {@link #capability()} 里的 {@code updateAvailable}。
 *
 * <p><b>后端只做两件事，绝不自己动手下载/换产物/杀进程</b>：
 * <ol>
 *   <li>触发——分离式拉起 {@code FH_DEPLOY_LAUNCHER}（Windows 的 {@code deploy-now.cmd}），由它去跑
 *       {@code myhome-windows.ps1 -Action Update}。<b>必须分离</b>：Update 的第一步就是
 *       {@code taskkill /T /F} 掉后端这棵进程树，部署进程若挂在后端下面会被连带杀死，更新就烂尾了。
 *       launcher 内部用 {@code start} 把 PowerShell 甩成独立进程，后端这边的 {@code cmd /c} 包装随即退出。</li>
 *   <li>读进度——launcher 拉起的那份 PowerShell 把每一步实时写进 {@code FH_DEPLOY_PROGRESS_FILE}
 *       （{@code logs/deploy-status.json}，原子写）。本服务只把这个文件原样读出来回吐给前端；
 *       同一份 PowerShell 跑到终态时还会把这一条追加进 {@code FH_DEPLOY_HISTORY_FILE}
 *       （{@code logs/deploy-history.json}），{@link #getHistory()} 只读它给超管看部署历史。</li>
 * </ol>
 *
 * <p><b>为什么进度靠文件而不是内存</b>：Update 会把后端杀掉重启，触发部署的这个 JVM 根本活不到部署结束。
 * 进度落在磁盘上，<b>重启后的新后端读的是同一个文件</b>，前端轮询就能无缝接上——中间只有前后端重启那几秒
 * 轮询连不上（前端显示「服务重启中」并继续重试），两边都起来后自动恢复。
 *
 * <p><b>环境未接入即禁用</b>：开发机没有 launcher / progress-file 两个环境变量，{@link #isEnabled()} 为 false，
 * 前端不显示入口、触发接口直接抛 {@link ErrorCode#DEPLOY_NOT_AVAILABLE}。刻意不做成「开发机也能假装部署」的开关。
 */
@Slf4j
@Service
public class DeployService {

    /** 一次部署最长认定「进行中」的时长；超过就当上一次烂尾了，允许重新触发（真并发另有 ps1 的 operation.lock 兜底）。 */
    private static final long STALE_RUNNING_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 探测 GitHub Release 最新版本（version.json）的 HTTP 超时；只读一个几百字节的小文件，短超时即可。 */
    private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(8);

    private final ObjectMapper objectMapper;
    private final ChangeLogService changeLogService;
    private final HttpClient httpClient;
    private final String launcher;
    private final String progressFile;
    private final String historyFile;
    /**
     * 安装根目录（引擎注入 {@code FH_INSTALL_DIR}，Spring 松弛绑定到属性 {@code fh.install-dir}——注意不是
     * {@code fh.deploy.install-dir}，那需要 {@code FH_DEPLOY_INSTALL_DIR}，引擎并不注入该名字）。
     * 运行版本戳读 {@code <install-dir>/app/version.json}——那是引擎冷启动/更新时解包并落盘的产物清单，
     * 记录当前跑着的 payload 版本与两仓 commit。开发机没有该目录时留空。
     */
    private final String installDir;
    /**
     * GitHub Release 更新通道的仓库（引擎注入 {@code FH_DEPLOY_RELEASE_REPO}，形如 {@code owner/repo}）。
     * 最新版本戳取 {@code https://github.com/<release-repo>/releases/latest/download/version.json}。留空即未接入更新通道。
     */
    private final String releaseRepo;

    public DeployService(
            ObjectMapper objectMapper,
            ChangeLogService changeLogService,
            @Value("${fh.deploy.launcher:}") String launcher,
            @Value("${fh.deploy.progress-file:}") String progressFile,
            @Value("${fh.deploy.history-file:}") String historyFile,
            @Value("${fh.install-dir:}") String installDir,
            @Value("${fh.deploy.release-repo:}") String releaseRepo) {
        this.objectMapper = objectMapper;
        this.changeLogService = changeLogService;
        this.launcher = launcher == null ? "" : launcher.trim();
        this.progressFile = progressFile == null ? "" : progressFile.trim();
        this.historyFile = historyFile == null ? "" : historyFile.trim();
        this.installDir = installDir == null ? "" : installDir.trim();
        this.releaseRepo = releaseRepo == null ? "" : releaseRepo.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(RELEASE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** launcher 与 progress-file 都注入了才算接入了「一键部署」；缺任一都视为当前环境不支持在线更新。 */
    public boolean isEnabled() {
        return !launcher.isEmpty() && !progressFile.isEmpty();
    }

    /**
     * 部署管理能力探测：告诉前端「能不能部署、当前跑的是哪个版本、GitHub Release 上最新是哪个版本、要不要提示更新」。
     *
     * <p>运行版本读 {@code <FH_INSTALL_DIR>/app/version.json}（引擎解包产物时落盘的版本戳）；最新版本打
     * {@code https://github.com/<FH_DEPLOY_RELEASE_REPO>/releases/latest/download/version.json}。两者都是只读探测，
     * 后端<b>不下载、不换产物、不重启自己</b>——真正的拉取替换由「部署最新」分离拉起的引擎进程去做。
     *
     * <p>拿不到运行版本（开发机无安装目录）或连不上 Release（离线/限流）都只退化对应字段为空 + releaseError 文案，
     * 绝不抛异常打挂部署管理页。
     */
    public ObjectNode capability() {
        ObjectNode node = objectMapper.createObjectNode();
        boolean enabled = isEnabled();
        node.put("enabled", enabled);

        JsonNode running = readRunningVersion();
        String currentVersion = running == null ? "" : running.path("version").asText("");
        node.put("currentVersion", currentVersion);
        node.set("currentCommits", commitsOf(running));

        String latestVersion = "";
        ObjectNode latestCommits = objectMapper.createObjectNode();
        String releaseError = null;
        if (enabled && !releaseRepo.isEmpty()) {
            JsonNode latest = fetchLatestRelease();
            if (latest == null) {
                releaseError = "无法连接 GitHub Release 获取最新版本，请稍后重试或检查网络";
            } else {
                latestVersion = latest.path("version").asText("");
                latestCommits = commitsOf(latest);
            }
        }
        node.put("latestVersion", latestVersion);
        node.set("latestCommits", latestCommits);
        node.put("updateAvailable",
                !currentVersion.isEmpty() && !latestVersion.isEmpty() && !currentVersion.equals(latestVersion));
        node.put("checkedAt", Instant.now().toString());
        if (releaseError == null) {
            node.putNull("releaseError");
        } else {
            node.put("releaseError", releaseError);
        }
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

    /**
     * 打 GitHub Release 最新版本戳：{@code https://github.com/<release-repo>/releases/latest/download/version.json}。
     * {@code /releases/latest/download/<asset>} 会 302 到最新发布版的同名资产，故无需先查 release 列表。
     * 任何失败（超时/断网/限流/非 200/解析错/根非对象）都只 warn 并返回 null，调用方据此退化。
     */
    private JsonNode fetchLatestRelease() {
        if (releaseRepo.isEmpty()) {
            return null;
        }
        String url = "https://github.com/" + releaseRepo + "/releases/latest/download/version.json";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(RELEASE_TIMEOUT)
                    .header("User-Agent", "family-home-server")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("获取最新发布版本失败: HTTP {} url={}", response.statusCode(), url);
                return null;
            }
            JsonNode node = objectMapper.readTree(response.body());
            return node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("获取最新发布版本异常（忽略）: {}", e.toString());
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
     * 触发一次「部署最新」。同步做完「防重 + 写初始进度 + 分离拉起 launcher」后立即返回 runId，
     * 不等部署结束（后端马上会被 Update 杀掉，等也没意义）。
     */
    public synchronized String triggerLatest() {
        if (!isEnabled()) {
            throw BizException.of(ErrorCode.DEPLOY_NOT_AVAILABLE, "当前运行环境未接入一键部署，无法在线更新");
        }
        Path progress = Path.of(progressFile);
        JsonNode current = readProgressFile(progress);
        if (isRunning(current)) {
            throw BizException.of(ErrorCode.DEPLOY_IN_PROGRESS, "已有一次部署正在进行中，请等它结束或查看进度");
        }

        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ObjectNode status = objectMapper.createObjectNode();
        status.put("runId", runId);
        status.put("status", "running");
        status.put("phase", "正在启动部署进程");
        status.put("startedAt", now.toString());
        status.putNull("finishedAt");
        status.putNull("error");
        // 目标版本戳优先取 GitHub Release 最新 version.json（引擎随后也会用它下载替换）；连不上就退回变更记录里的两仓 HEAD。
        JsonNode latest = fetchLatestRelease();
        if (latest != null) {
            String targetVersion = latest.path("version").asText("");
            if (!targetVersion.isEmpty()) {
                status.put("targetVersion", targetVersion);
            }
            status.set("targetCommits", commitsOf(latest));
        } else {
            status.set("targetCommits", latestCommits());
        }
        status.set("resultCommits", objectMapper.createObjectNode());
        ArrayNode lines = status.putArray("lines");
        addLine(lines, now, "已在部署管理页点击「部署最新」，正在拉起部署脚本…", "info");
        writeProgress(progress, status);

        spawnLauncher(runId, progress);
        return runId;
    }

    /**
     * 读当前进度。runId 只用于前端关联自己触发的那次；文件本身就是「最近一次部署」的完整状态，
     * 无论请求哪个 runId 都原样返回，前端自行比对。文件还没被 launcher 写出来时给一个 pending 占位，
     * 让前端继续轮询而不是报错。
     */
    public JsonNode getProgress(String runId) {
        if (progressFile.isEmpty()) {
            throw BizException.of(ErrorCode.DEPLOY_NOT_AVAILABLE, "当前运行环境未接入一键部署，无法在线更新");
        }
        JsonNode node = readProgressFile(Path.of(progressFile));
        if (node != null) {
            return node;
        }
        ObjectNode pending = objectMapper.createObjectNode();
        pending.put("runId", runId == null ? "" : runId);
        pending.put("status", "running");
        pending.put("phase", "等待部署进程写入进度");
        pending.putArray("lines");
        return pending;
    }

    /**
     * 读部署历史：部署脚本每次跑到终态（done/failed）就把那一条完整状态追加进 {@code history-file}
     * （JSON 数组、新在前、上限 50）。本方法只读不写——<b>写历史的人是那个能活过后端之死的分离部署进程</b>，
     * 后端自己触发的这次根本等不到结束就被 Update 杀掉了。
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

    /** 从当前变更记录里取两仓最新 commit（= 这次要部署到的目标），拿不到就是空对象。 */
    private ObjectNode latestCommits() {
        ObjectNode targets = objectMapper.createObjectNode();
        try {
            JsonNode changeLog = changeLogService.getChangeLog();
            for (JsonNode repo : changeLog.path("repositories")) {
                String key = repo.path("key").asText("");
                JsonNode commits = repo.path("commits");
                if (!key.isEmpty() && commits.isArray() && !commits.isEmpty()) {
                    String hash = commits.get(0).path("hash").asText("");
                    if (!hash.isEmpty()) {
                        targets.put(key, hash);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("部署触发时读取目标 commit 失败，忽略: {}", e.toString());
        }
        return targets;
    }

    /** 分离式拉起 launcher：Windows 走 cmd /c（launcher 内部再用 start 甩开 PowerShell），其余走 /bin/sh。 */
    private void spawnLauncher(String runId, Path progress) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command = windows
                ? List.of("cmd.exe", "/c", launcher)
                : List.of("/bin/sh", launcher);
        try {
            Path parent = progress.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path launcherLog = (parent == null ? Path.of(".") : parent).resolve("deploy-launcher.out");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("FH_DEPLOY_RUN_ID", runId);
            builder.redirectErrorStream(true);
            builder.redirectOutput(launcherLog.toFile());
            Process process = builder.start();
            // launcher 只是「点火」的包装（Windows 里 start 后立即 exit /b 0），应很快退出；
            // 等它一小会儿既回收包装进程，又能在点火失败（路径错等）时拿到退出码。真正的部署已甩到独立进程里跑。
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (exited && process.exitValue() != 0) {
                log.warn("部署 launcher 退出码非 0: {}", process.exitValue());
                ObjectNode status = readProgressFile(progress) instanceof ObjectNode obj ? obj : objectMapper.createObjectNode();
                status.put("status", "failed");
                status.put("phase", "拉起部署脚本失败");
                status.put("error", "部署脚本启动失败（退出码 " + process.exitValue() + "），请查看 logs/deploy-launcher.out");
                status.put("finishedAt", Instant.now().toString());
                ArrayNode lines = status.has("lines") && status.get("lines").isArray()
                        ? (ArrayNode) status.get("lines") : status.putArray("lines");
                addLine(lines, Instant.now(), "拉起部署脚本失败，退出码 " + process.exitValue(), "error");
                writeProgress(progress, status);
                throw BizException.of(ErrorCode.DEPLOY_NOT_AVAILABLE, "拉起部署脚本失败，请查看 logs/deploy-launcher.out");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("触发部署失败", e);
            throw BizException.of(ErrorCode.DEPLOY_NOT_AVAILABLE, "触发部署失败：" + e.getMessage());
        }
    }

    private boolean isRunning(JsonNode node) {
        if (node == null || !"running".equals(node.path("status").asText(""))) {
            return false;
        }
        String startedAt = node.path("startedAt").asText("");
        try {
            long age = System.currentTimeMillis() - Instant.parse(startedAt).toEpochMilli();
            return age >= 0 && age < STALE_RUNNING_MILLIS;
        } catch (Exception e) {
            // startedAt 缺失/非法：当烂尾处理，允许重新触发。
            return false;
        }
    }

    private JsonNode readProgressFile(Path progress) {
        try {
            if (!Files.isRegularFile(progress)) {
                return null;
            }
            return objectMapper.readTree(Files.readString(progress, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 可能正撞上原子写的极短窗口；这一次读失败就当作没有，前端下一轮再读。
            log.debug("读取部署进度文件失败（忽略，将在下次轮询重试）: {}", e.toString());
            return null;
        }
    }

    private void writeProgress(Path progress, JsonNode node) {
        try {
            Path absolute = progress.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("写入部署进度文件失败: {}", e.toString());
        }
    }

    private void addLine(ArrayNode lines, Instant ts, String message, String level) {
        ObjectNode line = lines.addObject();
        line.put("ts", ts.toString());
        line.put("message", message);
        line.put("level", level);
    }
}
