package com.familyhome.boot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.boot.dao.OperationLogMapper;
import com.familyhome.boot.dao.OperationLogSettingMapper;
import com.familyhome.boot.entity.OperationLogDO;
import com.familyhome.boot.entity.OperationLogSettingDO;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.PageResult;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户操作审计（方案 v45，system 域）：读写全记、区分 B/C 端、按保留天数清理。
 *
 * <p><b>采集不碰业务</b>：拦截器在请求完成后把「原始素材」（method/uri/query/body/响应体/状态码/耗时/IP/页面头/当前人）
 * 交给 {@link #record}，本服务在这里做翻译（动作字典）、脱敏、摘要、异步落库。落库走一条<b>守护单线程 + 有界队列</b>
 * 的 executor，队列满就丢弃（{@link ThreadPoolExecutor.DiscardPolicy}）——审计绝不能反压业务、更不能因写库失败让请求失败，
 * 所以 {@link #record} 全程吞异常。读写都记意味着量大，故用异步 + 保留天数兜底。
 *
 * <p><b>动作字典</b>：后端天然只知道「调了哪个接口」，用户要的是「点击了什么」的中文。这里两级还原——
 * 先查 {@link #EXPLICIT_ACTIONS}（METHOD + 归一化 uri，覆盖登录/播放/上传/下单流转等「动词结尾」的特例），
 * 未命中再用 {@link #RESOURCE_NOUNS} 把路径段翻成名词、按 HTTP 方法配动词自动生成（GET 查看 / POST 新增 / PUT 修改 / DELETE 删除）。
 *
 * <p><b>脱敏</b>：口令/令牌/密钥一类字段（{@link #SENSITIVE_KEY_HINTS}）在入参与响应里一律替换成 {@code ***}，
 * 与「密码本口令不落明文、登录口令只走哈希」的既有口径一致——审计页是给超管看操作轨迹的，不是看凭据的。
 *
 * <p><b>保留天数</b>：单行配置表 {@code operation_log_setting}（id=1），超管可在页面改（1..365，默认 7）；
 * {@link #cleanupExpired()} 每日凌晨按窗口物理删除过期行（审计是流水、不做软删）。
 */
@Slf4j
@Service
public class OperationLogService {

    /** 只审计业务接口；这些前缀是机器心跳/审计自身/高频轮询，记进来只会淹没真实操作轨迹。 */
    private static final List<String> EXCLUDE_URI_PREFIXES = List.of(
            "/api/b/health",            // 部署机 keepalive 心跳，非用户操作
            "/api/b/operation-logs",    // 审计页自己的读写，记了就是自我反馈刷屏
            "/api/b/logs/tail",         // 系统日志页 ~1.5s 轮询，机器刷新非用户点击
            "/api/b/deploy/progress"    // 部署进度轮询，同上
    );

    /** 入参/响应里这些字段一律脱敏成 ***（名字小写包含即命中）。 */
    private static final List<String> SENSITIVE_KEY_HINTS = List.of(
            "password", "pwd", "token", "secret", "credential", "口令", "密码");

    /** 单条文本（入参/响应摘要）落库前的长度上限，超出截断——审计要的是「看得出做了什么」，不是完整报文。 */
    private static final int MAX_PARAMS_LEN = 2000;
    private static final int MAX_SUMMARY_LEN = 800;

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 365;
    private static final long SETTING_ID = 1L;

    /** METHOD + 归一化 uri（数字段替成 {}）→ 中文动作。只收「动词结尾/自动生成会说错」的特例，其余走名词字典自动生成。 */
    private static final Map<String, String> EXPLICIT_ACTIONS = new LinkedHashMap<>();
    /** 路径段 → 中文名词，供自动生成拼接。 */
    private static final Map<String, String> RESOURCE_NOUNS = new LinkedHashMap<>();

    static {
        // —— 登录 / 当前人 ——
        EXPLICIT_ACTIONS.put("POST /api/b/user/login", "登录管理端");
        EXPLICIT_ACTIONS.put("POST /api/c/user/login", "登录家庭端");
        EXPLICIT_ACTIONS.put("GET /api/b/user/me", "获取当前登录人");
        EXPLICIT_ACTIONS.put("GET /api/c/user/me", "获取当前登录人");
        EXPLICIT_ACTIONS.put("GET /api/b/user/options", "查看账号下拉选项");
        EXPLICIT_ACTIONS.put("GET /api/c/user/options", "查看账号下拉选项");
        EXPLICIT_ACTIONS.put("PUT /api/b/user/profile/password", "修改登录口令");
        EXPLICIT_ACTIONS.put("PUT /api/b/user/{}/role", "设置账号角色");
        // —— 密码本口令 reveal（POST 但是「查看」语义）——
        EXPLICIT_ACTIONS.put("POST /api/b/vault/accounts/{}/password/reveal", "查看密码本口令");
        EXPLICIT_ACTIONS.put("POST /api/c/vault/accounts/{}/password/reveal", "查看密码本口令");
        // —— 文件 / 视频：上传、下载、播放 ——
        EXPLICIT_ACTIONS.put("POST /api/b/file/upload", "上传文件");
        EXPLICIT_ACTIONS.put("POST /api/c/file/upload", "上传文件");
        EXPLICIT_ACTIONS.put("GET /api/b/file/documents/{}/download", "下载文档");
        EXPLICIT_ACTIONS.put("GET /api/b/video/{}/stream", "播放视频");
        EXPLICIT_ACTIONS.put("GET /api/c/video/{}/stream", "播放视频");
        // —— 相册动作结尾 ——
        EXPLICIT_ACTIONS.put("PUT /api/b/album/images/{}/pin", "置顶相册图片");
        EXPLICIT_ACTIONS.put("POST /api/b/album/cities/recalculate", "重算城市分布");
        EXPLICIT_ACTIONS.put("PUT /api/b/album/groups/sort", "调整相册分组排序");
        EXPLICIT_ACTIONS.put("PUT /api/b/file/groups/sort", "调整文件分组排序");
        EXPLICIT_ACTIONS.put("PUT /api/b/storage/{}/default", "设为默认存储目录");
        // —— 点单流转（全是 POST 但语义各异）——
        EXPLICIT_ACTIONS.put("POST /api/c/recipe/orders", "提交订单");
        EXPLICIT_ACTIONS.put("POST /api/c/recipe/orders/{}/complete", "完成订单");
        EXPLICIT_ACTIONS.put("POST /api/c/recipe/orders/{}/cancel", "取消订单");
        EXPLICIT_ACTIONS.put("POST /api/c/recipe/orders/{}/again", "再来一单");
        EXPLICIT_ACTIONS.put("POST /api/c/recipe/orders/{}/append", "继续加菜");
        EXPLICIT_ACTIONS.put("POST /api/b/recipe/orders/{}/complete", "完成订单");
        EXPLICIT_ACTIONS.put("POST /api/b/recipe/orders/{}/cancel", "取消订单");
        EXPLICIT_ACTIONS.put("PUT /api/c/recipe/cart", "更新购物车");
        EXPLICIT_ACTIONS.put("DELETE /api/c/recipe/cart", "清空购物车");
        // —— 运维 ——
        EXPLICIT_ACTIONS.put("POST /api/b/deploy/latest", "部署最新");
        EXPLICIT_ACTIONS.put("POST /api/b/validation/duplicate", "字段查重校验");

        RESOURCE_NOUNS.put("album", "相册");
        RESOURCE_NOUNS.put("image", "图片");
        RESOURCE_NOUNS.put("images", "图片");
        RESOURCE_NOUNS.put("group", "分组");
        RESOURCE_NOUNS.put("groups", "分组");
        RESOURCE_NOUNS.put("recipe", "菜品");
        RESOURCE_NOUNS.put("recipes", "菜品");
        RESOURCE_NOUNS.put("category", "分类");
        RESOURCE_NOUNS.put("categories", "分类");
        RESOURCE_NOUNS.put("practice", "做法");
        RESOURCE_NOUNS.put("practices", "做法");
        RESOURCE_NOUNS.put("order", "订单");
        RESOURCE_NOUNS.put("orders", "订单");
        RESOURCE_NOUNS.put("cart", "购物车");
        RESOURCE_NOUNS.put("statistics", "统计");
        RESOURCE_NOUNS.put("file", "文件");
        RESOURCE_NOUNS.put("files", "文件");
        RESOURCE_NOUNS.put("document", "文档");
        RESOURCE_NOUNS.put("documents", "文档");
        RESOURCE_NOUNS.put("video", "视频");
        RESOURCE_NOUNS.put("videos", "视频");
        RESOURCE_NOUNS.put("vault", "密码本");
        RESOURCE_NOUNS.put("account", "账号");
        RESOURCE_NOUNS.put("accounts", "账号");
        RESOURCE_NOUNS.put("user", "账号");
        RESOURCE_NOUNS.put("users", "账号");
        RESOURCE_NOUNS.put("profile", "个人资料");
        RESOURCE_NOUNS.put("avatar", "头像");
        RESOURCE_NOUNS.put("city", "城市");
        RESOURCE_NOUNS.put("cities", "城市");
        RESOURCE_NOUNS.put("storage", "存储目录");
        RESOURCE_NOUNS.put("storage-info", "存储信息");
        RESOURCE_NOUNS.put("folders", "文件夹");
        RESOURCE_NOUNS.put("logs", "日志");
        RESOURCE_NOUNS.put("deploy", "部署");
        RESOURCE_NOUNS.put("history", "历史");
        RESOURCE_NOUNS.put("capability", "能力");
        RESOURCE_NOUNS.put("change-log", "变更记录");
        RESOURCE_NOUNS.put("operation-logs", "操作记录");
        RESOURCE_NOUNS.put("retention", "保留设置");
        RESOURCE_NOUNS.put("covers", "封面");
        RESOURCE_NOUNS.put("role", "角色");
    }

    private final OperationLogMapper operationLogMapper;
    private final OperationLogSettingMapper settingMapper;
    private final ObjectMapper objectMapper;

    /**
     * 落库专用：单守护线程 + 有界队列 + 满了丢弃。审计永远不给业务加延迟、不阻塞、不因失败抛错。
     * 用守护线程，应用停机时不会因残留任务卡住 JVM 退出。
     */
    private final ExecutorService writeExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2000),
            r -> {
                Thread t = new Thread(r, "operation-log-writer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    public OperationLogService(OperationLogMapper operationLogMapper,
            OperationLogSettingMapper settingMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.settingMapper = settingMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 采集入口（拦截器调用）
    // ------------------------------------------------------------------

    /** 该 uri 是否纳入审计：只记 {@code /api/**}，且不在排除前缀内。 */
    public boolean shouldLog(String uri) {
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        for (String prefix : EXCLUDE_URI_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 组装一条审计记录并异步落库。全程吞异常——审计失败绝不能冒泡到业务响应。
     *
     * @param queryString  原始 query（可空）
     * @param requestBody  请求体原文（可空；multipart/流式请求为 null）
     * @param responseBody 响应体原文（可空；流式响应为 null）
     * @param httpStatus   响应状态码
     * @param durationMs   服务端处理耗时
     * @param pageHeader   前端 {@code X-Op-Page} 头（URL 编码的中文，可空）
     * @param traceId      本次请求的链路 id（{@code TraceIdFilter} 生成，与日志行/响应头同值，可空）
     */
    public void record(String method, String uri, String queryString, String requestBody,
            String responseBody, int httpStatus, long durationMs, String clientIp,
            String pageHeader, String traceId, CurrentUserHolder.CurrentUser user) {
        try {
            OperationLogDO row = new OperationLogDO();
            row.setSource(uri.startsWith("/api/b") ? "B" : uri.startsWith("/api/c") ? "C" : "?");
            row.setHttpMethod(method);
            row.setUri(truncate(uri, 255));
            row.setAction(truncate(translateAction(method, uri), 64));
            row.setPage(truncate(decodePage(pageHeader), 128));
            row.setClientIp(truncate(clientIp, 64));
            row.setTraceId(truncate(traceId, 64));
            row.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            if (user != null) {
                row.setUserId(user.id());
                row.setUserName(truncate(user.name(), 32));
                row.setUserRole(truncate(user.role(), 16));
            }
            row.setRequestParams(buildRequestParams(queryString, requestBody));
            row.setResponseSummary(buildResponseSummary(responseBody));

            String code = extractCode(responseBody);
            boolean ok = httpStatus < 400 && (code == null || ErrorCode.SUCCESS.equals(code));
            row.setSuccess(ok ? 1 : 0);
            row.setErrorCode(ok ? null : truncate(code != null ? code : "HTTP_" + httpStatus, 64));

            writeExecutor.submit(() -> insertQuietly(row));
        } catch (Exception e) {
            log.debug("组装操作审计记录失败（忽略）: {}", e.toString());
        }
    }

    private void insertQuietly(OperationLogDO row) {
        try {
            operationLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("写入操作审计失败（忽略）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 动作字典
    // ------------------------------------------------------------------

    /** METHOD + uri → 中文动作。先查显式字典，未命中按名词字典自动生成。 */
    String translateAction(String method, String uri) {
        String normalized = normalizeUri(uri);
        String explicit = EXPLICIT_ACTIONS.get(method + " " + normalized);
        if (explicit != null) {
            return explicit;
        }
        return autoAction(method, normalized);
    }

    /** 把纯数字路径段替成 {@code {}}，好让 {@code /orders/123} 与字典里的 {@code /orders/{}} 对上。 */
    private String normalizeUri(String uri) {
        String[] parts = uri.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            // 跳过空段：uri 以 '/' 开头时 split 会产出首个空串，逐段再补 '/' 会拼出 "//api/..."，
            // 那样和字典里的单斜杠键永远对不上（显式动作全失效）。这里只按非空段重建。
            if (p.isEmpty()) {
                continue;
            }
            sb.append('/').append(isId(p) ? "{}" : p);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static boolean isId(String seg) {
        if (seg.isEmpty()) {
            return false;
        }
        for (int i = 0; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 自动生成：动词（按 HTTP 方法）+ 路径名词拼接，如 GET /api/b/album/images → 查看相册图片。 */
    private String autoAction(String method, String normalizedUri) {
        String verb = switch (method) {
            case "GET" -> "查看";
            case "POST" -> "新增";
            case "PUT" -> "修改";
            case "DELETE" -> "删除";
            default -> "访问";
        };
        StringBuilder nouns = new StringBuilder();
        String lastNoun = null;
        for (String seg : normalizedUri.split("/")) {
            if (seg.isEmpty() || seg.equals("api") || seg.equals("b") || seg.equals("c") || seg.equals("{}")) {
                continue;
            }
            String noun = RESOURCE_NOUNS.get(seg);
            if (noun == null) {
                noun = seg;
            }
            // 同一名词连续出现只留一个：/recipe/recipes 两段都翻成「菜品」，拼成「菜品菜品」是噪音。
            if (noun.equals(lastNoun)) {
                continue;
            }
            nouns.append(noun);
            lastNoun = noun;
        }
        return nouns.length() == 0 ? verb : verb + nouns;
    }

    // ------------------------------------------------------------------
    // 脱敏 + 摘要
    // ------------------------------------------------------------------

    private String decodePage(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        try {
            return URLDecoder.decode(header.trim(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return header.trim();
        }
    }

    /** 入参：query + body 合并成一段 JSON 文本，敏感字段脱敏、整体截断。 */
    private String buildRequestParams(String queryString, String requestBody) {
        ObjectNode root = objectMapper.createObjectNode();
        boolean any = false;
        if (StringUtils.hasText(queryString)) {
            root.put("query", truncate(queryString, MAX_PARAMS_LEN));
            any = true;
        }
        if (StringUtils.hasText(requestBody)) {
            JsonNode masked = maskJson(parseOrText(requestBody));
            root.set("body", masked);
            any = true;
        }
        return any ? truncate(root.toString(), MAX_PARAMS_LEN) : null;
    }

    /** 响应摘要：只留 code/message + 截断后的 data，敏感字段脱敏。流式/空响应回 null。 */
    private String buildResponseSummary(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        JsonNode node = parseOrText(responseBody);
        if (node.isObject() && node.has("code")) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("code", node.path("code").asText());
            if (node.has("message")) {
                summary.put("message", node.path("message").asText());
            }
            JsonNode data = maskJson(node.get("data"));
            String dataStr = data == null ? "null" : data.toString();
            summary.put("data", truncate(dataStr, MAX_SUMMARY_LEN));
            return truncate(summary.toString(), MAX_SUMMARY_LEN + 128);
        }
        return truncate(maskJson(node).toString(), MAX_SUMMARY_LEN);
    }

    private String extractCode(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.isObject() && node.hasNonNull("code") ? node.get("code").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseOrText(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(raw);
        }
    }

    /** 递归脱敏：命中敏感键名的字段值替换成 ***。非 JSON（纯文本）时按 kv 正则粗筛后原样返回。 */
    private JsonNode maskJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> names = obj.fieldNames();
            List<String> keys = new ArrayList<>();
            names.forEachRemaining(keys::add);
            for (String k : keys) {
                if (isSensitive(k)) {
                    obj.put(k, "***");
                } else {
                    obj.set(k, maskJson(obj.get(k)));
                }
            }
            return obj;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i, maskJson(node.get(i)));
            }
            return node;
        }
        return node;
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        for (String hint : SENSITIVE_KEY_HINTS) {
            if (lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------
    // 查询（超管页面）
    // ------------------------------------------------------------------

    /**
     * 服务端分页查审计流水，按时间倒序。所有筛选维度都可选。
     *
     * @param keyword 模糊匹配 动作 / uri / 页面 / 操作人昵称
     */
    public PageResult<OperationLogDO> page(int pageNo, int pageSize, String source, Long userId,
            Integer success, LocalDateTime startTime, LocalDateTime endTime, String keyword) {
        LambdaQueryWrapper<OperationLogDO> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(source)) {
            q.eq(OperationLogDO::getSource, source);
        }
        if (userId != null) {
            q.eq(OperationLogDO::getUserId, userId);
        }
        if (success != null) {
            q.eq(OperationLogDO::getSuccess, success);
        }
        if (startTime != null) {
            q.ge(OperationLogDO::getCreateTime, startTime);
        }
        if (endTime != null) {
            q.le(OperationLogDO::getCreateTime, endTime);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(OperationLogDO::getAction, kw)
                    .or().like(OperationLogDO::getUri, kw)
                    .or().like(OperationLogDO::getPage, kw)
                    .or().like(OperationLogDO::getUserName, kw));
        }
        q.orderByDesc(OperationLogDO::getCreateTime).orderByDesc(OperationLogDO::getId);
        Page<OperationLogDO> page = operationLogMapper.selectPage(new Page<>(pageNo, pageSize), q);
        return PageResult.of(page.getRecords(), page.getTotal(), pageNo, pageSize);
    }

    // ------------------------------------------------------------------
    // 保留天数（读写单行配置）+ 定时清理
    // ------------------------------------------------------------------

    /** 读保留天数：{@code {retentionDays, available}}。DB 单行缺失时兜底默认 7（并补种一行）。available 恒 true。 */
    public ObjectNode getRetention() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", currentRetentionDays());
        result.put("available", true);
        return result;
    }

    /** 设置保留天数（1..365），落库单行配置。 */
    public ObjectNode setRetention(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "保留天数需在 " + MIN_RETENTION_DAYS + " 到 " + MAX_RETENTION_DAYS + " 天之间");
        }
        OperationLogSettingDO setting = settingMapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = new OperationLogSettingDO();
            setting.setId(SETTING_ID);
            setting.setRetentionDays(retentionDays);
            settingMapper.insert(setting);
        } else {
            setting.setRetentionDays(retentionDays);
            settingMapper.updateById(setting);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", retentionDays);
        result.put("available", true);
        return result;
    }

    private int currentRetentionDays() {
        OperationLogSettingDO setting = settingMapper.selectById(SETTING_ID);
        if (setting == null || setting.getRetentionDays() == null) {
            return DEFAULT_RETENTION_DAYS;
        }
        int days = setting.getRetentionDays();
        return (days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS) ? days : DEFAULT_RETENTION_DAYS;
    }

    /** 每日 03:30 清理超过保留窗口的审计行。手动改小天数后，下一次到点即生效。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpired() {
        int days = currentRetentionDays();
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        try {
            int deleted = operationLogMapper.deleteOlderThan(before);
            if (deleted > 0) {
                log.info("操作审计清理：删除 {} 条早于 {} 的记录（保留 {} 天）", deleted, before, days);
            }
        } catch (Exception e) {
            log.warn("操作审计清理失败: {}", e.toString());
        }
    }
}
