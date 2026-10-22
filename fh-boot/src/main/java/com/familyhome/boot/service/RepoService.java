package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 「仓库浏览器」通用引擎：在线浏览 / 编辑一个 git 工作副本里的文本文件，并一键推送到远端。
 *
 * <p><b>为什么是通用引擎而不是每个仓库各写一份</b>：知识库（方案 v49）、后端代码仓库、前端代码仓库（方案 v51）
 * 要的是同一件事——列文件树、读写文本、跑 git add/commit/push，只是各自指向不同的目录、用不同的权限码门禁、
 * 提交信息默认值不同。所以把这套逻辑收敛到本类，每个调用方（{@link KnowledgeService} / {@link CodeRepoService}）
 * 只负责把自己那份配置解析成一个 {@link RepoHandle} 交进来，绝不各抄一遍 450 行 git 封装。
 *
 * <p><b>内容根目录 = 一个已有的 git 工作副本</b>，由 {@link RepoHandle#dir()} 指定。刻意不落库、不新建表：
 * 正文就是仓库里的文件本身，本服务只做「列文件树 / 读写文本文件 / 跑 git」，是文件系统的薄封装，与
 * {@link LogService} tail 日志文件、{@link DeployService} 读进度文件同一口径——后端不持有内容，只代理磁盘与 git。
 *
 * <p><b>环境未配置即优雅退化</b>：{@code dir} 为 null（开发机默认）时 {@link #available} 为 false，
 * 文件树接口回 {@code {available:false, tree:[]}}，前端显示空态；读 / 写 / 推送接口直接 400 拒绝，绝不"假装成功"。
 *
 * <p><b>可编辑范围 = 文本白名单</b>（{@link #EDITABLE_EXT}：md/txt/sh/py/java/ts/json/yml…）。二进制
 * （png/jpg/zip…）在文件树里照常列出但标 {@code editable=false}，前端不给打开编辑器的入口——只读地"看得见"。
 *
 * <p><b>保存与推送是两件事</b>：{@link #writeFile} 只把内容写回本地工作副本（等价于存盘），会不会同步到远端由
 * 另点「推送到 Git」决定——{@link #gitStatus} 先列改动清单，{@link #push} 再 {@code add -A + commit + push} 一次做完。
 *
 * <p><b>推送为什么能进程内跑，而部署必须分离进程</b>：{@link DeployService} 的 Update 第一步就 taskkill 掉后端自己，
 * 所以只能分离拉起 launcher；而 git add/commit/push <b>不会杀掉后端</b>，直接在当前 JVM 里 {@link ProcessBuilder}
 * 同步跑完拿退出码即可。远端是 SSH 地址时需要私钥，用可选的 {@link RepoHandle#gitSshCommand()} 注入
 * {@code GIT_SSH_COMMAND}（个人 GitHub 走独立 key），留空则用 git 默认凭据链。
 *
 * <p><b>安全边界</b>：所有读写都先过 {@link #resolveSafe}——相对路径、拒绝绝对路径与 {@code ..}、规范化后必须仍在
 * 仓库根内，杜绝路径穿越读到 {@code /etc/passwd} 或写出仓库外。{@code .git} 目录整体从文件树里排除、也不允许读写。
 */
@Slf4j
@Service
public class RepoService {

    /** 可在线读写的文本扩展名白名单（小写、含点）。不在此列的一律视为二进制、只列不可编辑。 */
    private static final Set<String> EDITABLE_EXT = Set.of(
            ".md", ".markdown", ".txt", ".sh", ".bash", ".py", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx",
            ".java", ".kt", ".kts", ".json", ".yml", ".yaml", ".xml", ".html", ".htm", ".css", ".scss", ".less",
            ".cmd", ".bat", ".ps1", ".properties", ".conf", ".ini", ".sql", ".csv", ".log",
            ".gitignore", ".env", ".toml", ".gradle", ".vue", ".svelte");

    /** 单个文件在线读取的字节上限（2MB）：都是文本源码，超此多为误放的大文件，直接拒绝而非灌进浏览器。 */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    /** git 命令超时（秒）：add/commit 很快，push 走网络留足余量。 */
    private static final long GIT_TIMEOUT_SECONDS = 120;

    private final ObjectMapper objectMapper;

    public RepoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 一个仓库句柄：把「这个仓库在哪、用哪把 SSH key、叫什么名字、默认提交信息」四件事打包，交给本引擎操作。
     *
     * @param dir              仓库工作副本根目录；未配置或不是真实目录时为 {@code null}（优雅退化的锚点）。
     * @param gitSshCommand    可选，注入 {@code GIT_SSH_COMMAND} 指定私钥；留空用 git 默认凭据链。
     * @param label            仓库中文名，只用于错误提示与默认提交信息（如「知识库」「后端代码仓库」）。
     * @param defaultCommitMsg 推送时未填提交信息则用它。
     */
    public record RepoHandle(Path dir, String gitSshCommand, String label, String defaultCommitMsg) {
    }

    /** 把配置的目录字符串解析成 {@link RepoHandle}。dirConfig 留空或不是真实目录 → dir=null（优雅退化）。 */
    public static RepoHandle handle(String dirConfig, String gitSshCommand, String label, String defaultCommitMsg) {
        String trimmed = dirConfig == null ? "" : dirConfig.trim();
        Path dir = null;
        if (!trimmed.isEmpty()) {
            Path candidate = Path.of(trimmed).toAbsolutePath().normalize();
            dir = Files.isDirectory(candidate) ? candidate : null;
        }
        return new RepoHandle(dir, gitSshCommand == null ? "" : gitSshCommand.trim(), label, defaultCommitMsg);
    }

    /** 本运行环境是否接入了这个仓库（dir 已配置且目录存在）。前端据此决定显示文件树还是空态。 */
    public boolean available(RepoHandle handle) {
        return handle != null && handle.dir() != null;
    }

    // ---------------------------------------------------------------------
    // 文件树
    // ---------------------------------------------------------------------

    /**
     * 递归列出仓库目录下的文件树（排除 {@code .git}）。返回 {@code {available, root, tree:[...]}}，
     * 每个节点 {@code {key(相对路径,/分隔), title(文件名), dir, editable, size, children[]}}——直接喂 antd Tree。
     * 目录在前、文件在后，各自按名字排序。目录不可用（开发机）时 {@code available=false, tree=[]}。
     */
    public ObjectNode getTree(RepoHandle handle) {
        ObjectNode result = objectMapper.createObjectNode();
        Path dir = handle == null ? null : handle.dir();
        if (dir == null) {
            result.put("available", false);
            result.put("root", "");
            result.putArray("tree");
            return result;
        }
        result.put("available", true);
        result.put("root", dir.getFileName() == null ? "" : dir.getFileName().toString());
        ArrayNode tree = result.putArray("tree");
        buildTree(dir, dir, tree);
        return result;
    }

    /** 递归填充某一层目录的子节点到 {@code out}。{@code base} 恒为仓库根，用于算相对路径 key。 */
    private void buildTree(Path base, Path current, ArrayNode out) {
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(current)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    // .git 整体排除：它是仓库元数据，不是可浏览的内容。
                    if (!".git".equals(name)) {
                        dirs.add(p);
                    }
                } else if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            });
        } catch (Exception e) {
            log.warn("列举仓库目录失败（跳过该层）: {}", e.toString());
            return;
        }
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        dirs.sort(byName);
        files.sort(byName);

        for (Path d : dirs) {
            ObjectNode node = out.addObject();
            node.put("key", relativize(base, d));
            node.put("title", d.getFileName().toString());
            node.put("dir", true);
            node.put("editable", false);
            ArrayNode children = node.putArray("children");
            buildTree(base, d, children);
        }
        for (Path f : files) {
            ObjectNode node = out.addObject();
            String name = f.getFileName().toString();
            node.put("key", relativize(base, f));
            node.put("title", name);
            node.put("dir", false);
            node.put("editable", isEditable(name));
            node.put("size", size(f));
        }
    }

    /** base 到 target 的相对路径，统一用 {@code /} 分隔（前端 key 与后端 resolve 都以它为准，跨平台一致）。 */
    private String relativize(Path base, Path target) {
        return base.relativize(target).toString().replace('\\', '/');
    }

    /** 按扩展名判断是否可在线编辑（文本白名单）。无扩展名的文件（如 Makefile、LICENSE、Dockerfile）也放行。 */
    private boolean isEditable(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot <= 0) {
            // 无扩展名（Makefile/LICENSE/Dockerfile…）：当纯文本处理，可编辑。
            return true;
        }
        return EDITABLE_EXT.contains(lower.substring(dot));
    }

    // ---------------------------------------------------------------------
    // 读 / 写单个文件
    // ---------------------------------------------------------------------

    /**
     * 读取一个文本文件的完整内容。返回 {@code {path, name, content, editable, size}}。
     * 非文本白名单的文件拒绝读取（前端本就不会给它开编辑器），超过 {@value #MAX_READ_BYTES} 字节也拒绝。
     */
    public ObjectNode readFile(RepoHandle handle, String relPath) {
        Path dir = requireRepo(handle);
        Path file = resolveSafe(dir, relPath);
        String name = file.getFileName().toString();
        if (!isEditable(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该文件类型不支持在线查看/编辑");
        }
        long size = size(file);
        if (size > MAX_READ_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件过大（超过 2MB），不支持在线编辑");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", relativize(dir, file));
        result.put("name", name);
        result.put("editable", true);
        result.put("size", size);
        try {
            result.put("content", Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取仓库文件失败: {}", e.toString());
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "读取文件失败，请稍后重试");
        }
        return result;
    }

    /**
     * 把一个文本文件的内容整体写回本地工作副本（保存）。仅接受文本白名单类型；写入用 UTF-8。
     * <b>只落本地磁盘，不碰 git</b>——是否推送由 {@link #push} 单独触发。返回 {@code {path, size}}。
     */
    public ObjectNode writeFile(RepoHandle handle, String relPath, String content) {
        Path dir = requireRepo(handle);
        Path file = resolveSafe(dir, relPath);
        String name = file.getFileName().toString();
        if (!isEditable(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该文件类型不支持在线编辑");
        }
        if (content == null) {
            content = "";
        }
        if (content.length() > MAX_READ_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "内容过大（超过 2MB），无法保存");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入仓库文件失败: {}", e.toString());
            throw BizException.of(ErrorCode.FILE_STORAGE_FAILED, "保存文件失败，请稍后重试");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", relativize(dir, file));
        result.put("size", size(file));
        return result;
    }

    /**
     * 把一个「相对路径」安全解析成仓库根内的真实路径。任何越界尝试（绝对路径、{@code ..}、
     * 规范化后逃出仓库根、命中 .git）都直接 400/404 拒绝。
     */
    private Path resolveSafe(Path dir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少文件路径");
        }
        String rel = relPath.trim().replace('\\', '/');
        if (rel.startsWith("/") || rel.contains("..")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的文件路径");
        }
        Path resolved = dir.resolve(rel).normalize();
        if (!resolved.startsWith(dir)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的文件路径");
        }
        // .git 是仓库元数据，不允许通过本接口读写。
        if (resolved.equals(dir) || resolved.startsWith(dir.resolve(".git"))) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的文件路径");
        }
        if (!Files.isRegularFile(resolved)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "文件不存在");
        }
        return resolved;
    }

    // ---------------------------------------------------------------------
    // git：状态 + 一键推送
    // ---------------------------------------------------------------------

    /**
     * 当前 git 工作副本状态：分支、待提交改动清单、是否干净、是否配了上游。用于「推送前预览改了哪些文件」。
     * 返回 {@code {available, git, branch, clean, hasUpstream, ahead, changed:[{path, status}]}}。
     * {@code git=false} 表示这个目录根本不是 git 仓库（此时只能本地编辑、无法推送）。
     */
    public ObjectNode gitStatus(RepoHandle handle) {
        Path dir = requireRepo(handle);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("available", true);

        GitResult revParse = runGit(handle, dir, List.of("rev-parse", "--is-inside-work-tree"));
        boolean isGit = revParse.exitCode == 0 && revParse.output.trim().equalsIgnoreCase("true");
        result.put("git", isGit);
        if (!isGit) {
            result.put("branch", "");
            result.put("clean", true);
            result.put("hasUpstream", false);
            result.put("ahead", 0);
            result.putArray("changed");
            return result;
        }

        String branch = runGit(handle, dir, List.of("rev-parse", "--abbrev-ref", "HEAD")).output.trim();
        result.put("branch", branch);

        // 是否配了上游（决定 push 能不能直接推、ahead 能不能算）。
        GitResult upstream = runGit(handle, dir, List.of("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"));
        boolean hasUpstream = upstream.exitCode == 0 && !upstream.output.isBlank();
        result.put("hasUpstream", hasUpstream);

        int ahead = 0;
        if (hasUpstream) {
            GitResult count = runGit(handle, dir, List.of("rev-list", "--count", "@{u}..HEAD"));
            if (count.exitCode == 0) {
                try {
                    ahead = Integer.parseInt(count.output.trim());
                } catch (NumberFormatException ignored) {
                    ahead = 0;
                }
            }
        }
        result.put("ahead", ahead);

        // porcelain 逐行 "<XY> <path>"；解析出改动清单供预览。
        ArrayNode changed = result.putArray("changed");
        GitResult status = runGit(handle, dir, List.of("status", "--porcelain"));
        boolean clean = status.exitCode == 0 && status.output.isBlank() && ahead == 0;
        if (status.exitCode == 0) {
            for (String line : status.output.split("\n")) {
                if (line.length() < 4) {
                    continue;
                }
                String xy = line.substring(0, 2);
                String path = line.substring(3).trim();
                // 重命名形如 "old -> new"，只展示新路径。
                int arrow = path.indexOf(" -> ");
                if (arrow >= 0) {
                    path = path.substring(arrow + 4);
                }
                ObjectNode item = changed.addObject();
                item.put("path", path);
                item.put("status", describeStatus(xy));
            }
        }
        result.put("clean", clean);
        return result;
    }

    /** 把 porcelain 的 XY 状态码翻成中文短语（前端直接展示）。 */
    private String describeStatus(String xy) {
        if (xy.contains("?")) {
            return "新增";
        }
        if (xy.contains("A")) {
            return "新增";
        }
        if (xy.contains("D")) {
            return "删除";
        }
        if (xy.contains("R")) {
            return "重命名";
        }
        if (xy.contains("M") || xy.contains("m")) {
            return "修改";
        }
        return "改动";
    }

    /**
     * 一键推送：{@code git add -A} → {@code git commit -m <message>} → {@code git push}。同步跑完再返回。
     *
     * <p>没有任何改动时 commit 会失败（"nothing to commit"），此时视为「无需推送」直接返回
     * {@code {pushed:false, committed:false, message:"没有需要推送的改动"}}，不报错。push 失败（网络 / 凭据 /
     * 非快进）时把 git 的原始输出如实回吐给前端展示，退出码非 0 即 {@code ok=false}。
     *
     * @param message 提交信息；留空时用句柄里的默认提交信息。
     */
    public ObjectNode push(RepoHandle handle, String message) {
        Path dir = requireRepo(handle);
        GitResult inside = runGit(handle, dir, List.of("rev-parse", "--is-inside-work-tree"));
        if (inside.exitCode != 0 || !inside.output.trim().equalsIgnoreCase("true")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, handle.label() + "目录不是 git 仓库，无法推送");
        }

        ObjectNode result = objectMapper.createObjectNode();
        String commitMessage = (message == null || message.isBlank())
                ? handle.defaultCommitMsg()
                : message.trim();

        // 1) add -A：把所有改动纳入暂存区。
        GitResult add = runGit(handle, dir, List.of("add", "-A"));
        if (add.exitCode != 0) {
            return pushFailed(result, "git add 失败", add.output);
        }

        // 2) commit：无改动时 git 退出码非 0，判为「没有需要推送的改动」，正常返回而非报错。
        GitResult commit = runGit(handle, dir, List.of("commit", "-m", commitMessage));
        boolean committed = commit.exitCode == 0;
        result.put("committed", committed);
        if (!committed) {
            String out = commit.output.toLowerCase(Locale.ROOT);
            if (out.contains("nothing to commit") || out.contains("no changes added")) {
                result.put("pushed", false);
                result.put("ok", true);
                result.put("message", "没有需要推送的改动");
                result.put("output", commit.output);
                return result;
            }
            return pushFailed(result, "git commit 失败", commit.output);
        }

        // 3) push：优先推到已配的上游；没有上游时退回 push 当前分支到 origin。
        String branch = runGit(handle, dir, List.of("rev-parse", "--abbrev-ref", "HEAD")).output.trim();
        GitResult upstream = runGit(handle, dir, List.of("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"));
        boolean hasUpstream = upstream.exitCode == 0 && !upstream.output.isBlank();
        List<String> pushArgs = hasUpstream
                ? List.of("push")
                : List.of("push", "-u", "origin", branch);
        GitResult pushResult = runGit(handle, dir, pushArgs);
        if (pushResult.exitCode != 0) {
            // 已经 commit 成功但 push 失败：本地领先一个提交，把实情回吐，用户可修好网络/凭据后重试推送。
            return pushFailed(result, "git push 失败（改动已在本地提交，可重试推送）", pushResult.output);
        }
        result.put("pushed", true);
        result.put("ok", true);
        result.put("message", "已推送到远端");
        result.put("output", pushResult.output);
        return result;
    }

    private ObjectNode pushFailed(ObjectNode result, String message, String output) {
        result.put("pushed", false);
        result.put("ok", false);
        result.put("message", message);
        result.put("output", output == null ? "" : output);
        return result;
    }

    /** 在仓库目录里同步跑一条 git 命令，合并 stdout/stderr，带超时。异常/超时都折成退出码非 0。 */
    private GitResult runGit(RepoHandle handle, Path dir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            String ssh = handle == null ? "" : handle.gitSshCommand();
            if (ssh != null && !ssh.isEmpty()) {
                builder.environment().put("GIT_SSH_COMMAND", ssh);
            }
            // 避免 git 卡在交互式凭据询问上（无凭据时直接失败而不是挂起到超时）。
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            String output;
            try (Stream<String> lines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).lines()) {
                output = lines.reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }
            boolean exited = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new GitResult(-1, output + "\n[git 命令超时]");
            }
            return new GitResult(process.exitValue(), output);
        } catch (Exception e) {
            log.warn("执行 git 命令失败 {}: {}", args, e.toString());
            return new GitResult(-1, "执行 git 命令失败：" + e.getMessage());
        }
    }

    /** 一条 git 命令的结果：退出码 + 合并输出。 */
    private record GitResult(int exitCode, String output) {
    }

    /** 取仓库目录，未配置时抛 400（读/写/git 这类"要动手"的接口用它，与文件树的优雅退化区分开）。 */
    private Path requireRepo(RepoHandle handle) {
        Path dir = handle == null ? null : handle.dir();
        if (dir == null) {
            String label = handle == null ? "仓库" : handle.label();
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前运行环境未配置" + label + "目录，无法操作");
        }
        return dir;
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }
}
