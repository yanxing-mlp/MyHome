package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 「变更记录」数据源：**实时优先从 GitHub 公开 API 拉，拉不到回退构建期烘焙的快照**。
 *
 * <p><b>为什么是实时</b>：用户口径「commit 是实时获取的 git 平台的记录」。两仓
 * （{@code yanxing-mlp/MyHome}、{@code yanxing-mlp/MyHome-React}）都是 public，
 * 走 {@code GET https://api.github.com/repos/{owner}/{repo}/commits} **无需 token**。
 * 用户要「查全部」，故按 {@code per_page=100} **循环翻页拉到空页为止**（不再只取最近一页），
 * 分页/时间/commitId 过滤都交给前端在这份全量列表上做。
 *
 * <p><b>为什么还留快照兜底</b>：离线部署机可能没外网，实时那一发会超时/失败。此时回退到
 * 构建期 {@code scripts/gen-change-log.py} 烘进 classpath 的 {@code git/change-log.json}
 * （随后端 JAR 发布），页面仍能显示上次打包时的历史，而不是整页报错。两条数据源产出**同一套响应形状**，
 * 前端不用区分（只多一个 {@code source} 字段告诉它这仓是「实时」还是「快照」）。
 *
 * <p><b>逐仓回退</b>：两仓各自独立尝试，一仓实时成功、另一仓失败也照常各显各的，不会因为一个仓挂了就整页降级。
 *
 * <p><b>短时缓存</b>：GitHub 未认证 API 每 IP 每小时 60 次，且这页只有 ADMIN 偶尔看，
 * 故结果按 {@code fh.change-log.cache-seconds} 缓存一小段，避免连点刷新打爆限额；缓存对成功/回退结果一视同仁。
 *
 * <p><b>零新依赖</b>：用 JDK 自带的 {@link HttpClient}（离线 {@code mvn -o} 仓库里没有别的 HTTP 客户端），
 * JSON 解析复用已注入的 {@link ObjectMapper}。
 */
@Slf4j
@Service
public class ChangeLogService {

    /** 构建期烘焙的兜底快照（gen-change-log.py 产出，已在 .gitignore）。 */
    private static final String SNAPSHOT_RESOURCE = "git/change-log.json";

    /** GitHub commits API 单页上限（官方最大 100）。 */
    private static final int PER_PAGE = 100;

    /** 翻页安全上限：100 页 = 1 万条提交，远超本产品两仓体量，纯粹防失控循环烧掉限流额度。 */
    private static final int MAX_PAGES = 100;

    /** 两仓固定配置：改仓库/分支时改这里（本产品就这两仓，故不做成外部配置项）。 */
    private record RepoSpec(String key, String label, String owner, String repo, String branch) {}

    private static final List<RepoSpec> REPOS = List.of(
            new RepoSpec("server", "后端 · family-home-server", "yanxing-mlp", "MyHome", "master"),
            new RepoSpec("web", "前端 · family-home-web", "yanxing-mlp", "MyHome-React", "master"));

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean githubEnabled;
    private final String apiBase;
    private final int timeoutSeconds;
    private final long cacheMillis;

    /** 上一次组装好的结果 + 组装时刻；volatile 足够（并发最坏是重复拉一次、last-write-wins，无正确性问题）。 */
    private volatile Cached cached;

    private record Cached(long atMillis, JsonNode node) {}

    public ChangeLogService(
            ObjectMapper objectMapper,
            @Value("${fh.change-log.github-enabled:true}") boolean githubEnabled,
            @Value("${fh.change-log.github-api-base:https://api.github.com}") String apiBase,
            @Value("${fh.change-log.timeout-seconds:8}") int timeoutSeconds,
            @Value("${fh.change-log.cache-seconds:60}") int cacheSeconds) {
        this.objectMapper = objectMapper;
        this.githubEnabled = githubEnabled;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheMillis = Math.max(0, cacheSeconds) * 1000L;
        // followRedirects.NORMAL：GitHub API 一般不重定向，正常化即可；连接超时短、读超时按请求头 timeout。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 供 controller 调用的入口：命中缓存直接返回，否则现组装一份并缓存。 */
    public JsonNode getChangeLog() {
        long now = System.currentTimeMillis();
        Cached local = cached;
        if (local != null && now - local.atMillis() < cacheMillis) {
            return local.node();
        }
        JsonNode node = assemble(now);
        cached = new Cached(now, node);
        return node;
    }

    private JsonNode assemble(long now) {
        JsonNode snapshot = loadSnapshot();
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode repos = root.putArray("repositories");

        int liveCount = 0;
        int snapshotCount = 0;
        for (RepoSpec spec : REPOS) {
            ObjectNode repoNode = null;
            String source = "unavailable";
            if (githubEnabled) {
                try {
                    repoNode = fetchLive(spec);
                    source = "live";
                    liveCount++;
                } catch (Exception e) {
                    // 任何失败（超时/断网/限流/非 200/解析错）都只 warn，不当异常抛——回退快照才是这仓的正解。
                    log.warn("变更记录实时拉取失败，回退快照: repo={}/{} branch={} err={}",
                            spec.owner(), spec.repo(), spec.branch(), e.toString());
                }
            }
            if (repoNode == null) {
                repoNode = fromSnapshot(snapshot, spec);
                if (repoNode != null) {
                    source = "snapshot";
                    snapshotCount++;
                } else {
                    repoNode = emptyRepo(spec);
                    source = "unavailable";
                }
            }
            repoNode.put("source", source);
            repos.add(repoNode);
        }

        // 顶层 source：全实时=live、全快照=snapshot、一半一半=mixed、有仓两源都没有=unavailable。
        String topSource;
        if (liveCount == REPOS.size()) {
            topSource = "live";
        } else if (snapshotCount == REPOS.size()) {
            topSource = "snapshot";
        } else if (liveCount + snapshotCount == REPOS.size()) {
            topSource = "mixed";
        } else {
            topSource = "unavailable";
        }
        root.put("source", topSource);

        // generatedAt：纯快照时沿用快照自己的生成时刻（那才是「快照生成于」的真值），其余情况用本次组装时刻。
        if ("snapshot".equals(topSource) && snapshot != null && snapshot.hasNonNull("generatedAt")) {
            root.put("generatedAt", snapshot.get("generatedAt").asText());
        } else {
            root.put("generatedAt", Instant.ofEpochMilli(now).toString());
        }
        return root;
    }

    /** 实时拉一仓：GitHub commits API **循环翻页拉全部**（per_page=100，拉到空页/不足一页为止），映射成与快照逐字同形的 repo 节点。 */
    private ObjectNode fetchLive(RepoSpec spec) throws Exception {
        ObjectNode repoNode = baseRepo(spec);
        ArrayNode commits = repoNode.putArray("commits");
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = apiBase + "/repos/" + spec.owner() + "/" + spec.repo()
                    + "/commits?sha=" + spec.branch() + "&per_page=" + PER_PAGE + "&page=" + page;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    // GitHub API 要求带 User-Agent，否则 403；Accept 固定 v3+json。
                    .header("User-Agent", "family-home-server")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GitHub API HTTP " + response.statusCode() + "（page " + page + "）");
            }
            JsonNode array = objectMapper.readTree(response.body());
            if (!array.isArray()) {
                throw new IllegalStateException("GitHub API 返回非数组");
            }
            if (array.size() == 0) {
                break; // 翻到头了
            }
            for (JsonNode item : array) {
                appendCommit(commits, item);
            }
            if (array.size() < PER_PAGE) {
                break; // 不足一页即最后一页，省掉一次必空请求
            }
            if (page == MAX_PAGES) {
                log.warn("变更记录实时拉取触达翻页上限，可能未取全: repo={}/{} branch={} maxPages={}",
                        spec.owner(), spec.repo(), spec.branch(), MAX_PAGES);
            }
        }
        repoNode.put("commitCount", commits.size());
        return repoNode;
    }

    /** 把 GitHub 单条 commit 映射成快照同形的一行追加进去；sha 为空的脏数据跳过。 */
    private void appendCommit(ArrayNode commits, JsonNode item) {
        JsonNode commit = item.path("commit");
        String sha = item.path("sha").asText("");
        if (sha.isEmpty()) {
            return;
        }
        String author = commit.path("author").path("name").asText("");
        if (author.isEmpty()) {
            // 有些提交 git author 名字缺失，退回 GitHub 登录名。
            author = item.path("author").path("login").asText("");
        }
        ObjectNode node = commits.addObject();
        node.put("hash", sha);
        node.put("shortHash", sha.length() >= 7 ? sha.substring(0, 7) : sha);
        node.put("author", author);
        node.put("date", commit.path("author").path("date").asText(""));
        // 与快照的 %s（subject）对齐：只取提交信息首行。
        node.put("message", firstLine(commit.path("message").asText("")));
    }

    /** 从烘焙快照里取出与本仓 key 匹配的那个 repo 节点（深拷贝一份，避免往共享树上写 source）。 */
    private ObjectNode fromSnapshot(JsonNode snapshot, RepoSpec spec) {
        if (snapshot == null) {
            return null;
        }
        JsonNode repos = snapshot.path("repositories");
        if (!repos.isArray()) {
            return null;
        }
        for (JsonNode repo : repos) {
            if (spec.key().equals(repo.path("key").asText(""))) {
                return repo.deepCopy();
            }
        }
        return null;
    }

    private JsonNode loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(SNAPSHOT_RESOURCE);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        } catch (Exception e) {
            log.warn("变更记录兜底快照读取失败: {}", e.toString());
            return null;
        }
    }

    private ObjectNode emptyRepo(RepoSpec spec) {
        ObjectNode repoNode = baseRepo(spec);
        repoNode.putArray("commits");
        repoNode.put("commitCount", 0);
        return repoNode;
    }

    private ObjectNode baseRepo(RepoSpec spec) {
        ObjectNode repoNode = objectMapper.createObjectNode();
        repoNode.put("key", spec.key());
        repoNode.put("label", spec.label());
        repoNode.put("branch", spec.branch());
        return repoNode;
    }

    private static String firstLine(String message) {
        int idx = message.indexOf('\n');
        return idx >= 0 ? message.substring(0, idx) : message;
    }
}
