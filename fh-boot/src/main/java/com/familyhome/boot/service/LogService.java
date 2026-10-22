package com.familyhome.boot.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.RollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.ArchiveRemover;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 实时系统日志：给超管在后台看后端与部署脚本写出的日志文件（方案 v43）。
 *
 * <p><b>日志目录的唯一锚点 = 后端自身日志文件 {@code logging.file.name} 的父目录。</b>部署脚本（Windows ps1 /
 * unix-deploy.py）都以 {@code prod} profile 起后端并注入 {@code LOGGING_FILE_NAME=<install>/logs/server.log}，
 * 而 {@code Deploy.log / backend.err.log / web-*.log / mysql-error.log} 等等都写在同一个 {@code logs} 目录里，
 * 所以只要拿到 {@code logging.file.name} 的父目录，就能把这一堆日志一并列出来。这个属性用 {@code @Value} 直接读：
 * docker-prod 走 application-prod.yml 里的字面值、源码直跑走环境变量覆盖（环境变量优先级高于 yml）、
 * 开发机两者皆无 → 留空 → {@link #logDir()} 为 null → 列表接口返回空数组、页面显示「当前环境未落日志文件」。
 *
 * <p><b>只读日志内容、绝不写日志文件</b>（与 {@link DeployService} 读进度文件同一口径）：日志的写入方是 logback 和
 * 部署脚本本身，本服务只做 tail。用 {@link RandomAccessFile} 以只读模式打开，允许与写入方并发共享读。
 * <b>唯一的写例外</b>是它自己的设置文件 {@code logs/log-settings.json}（保存超管在页面上设的「日志保留天数」），
 * 只写这一个 .json、原子写，从不碰任何 {@code *.log}——与 {@code deploy-status.json / deploy-history.json}
 * 这些同目录的 JSON 一样，天然被 {@code .log} 白名单排除在可 tail 的文件之外。
 *
 * <p><b>安全边界</b>：tail 只接受一个<b>文件名</b>（不是路径），严格拒绝任何含 {@code / \ ..} 或不以 {@code .log}
 * 结尾的输入，并把它解析到日志目录内、再确认规范化后仍在目录内，杜绝路径穿越读到 {@code /etc/passwd} 之类。
 * {@code .log} 白名单也天然把 {@code deploy-status.json / deploy-history.json} 这些 JSON 排除在外。
 *
 * <p><b>日志保留天数（方案 v44）</b>：删旧日志这件事一直由 logback 的 {@code rollingpolicy.max-history} 负责
 * （见 {@code application-prod.yml}：按天滚成 {@code server.log.yyyy-MM-dd.i.gz}，滚动/重启时清掉超过 N 天的归档，
 * 无需另写定时任务）。本服务把这个「N」做成超管可在页面上改的设置：<b>持久化在 {@code logs/log-settings.json}</b>
 * 跨重启留存，改动时<b>反射拿到运行中的 {@link TimeBasedRollingPolicy} 及其私有的 {@link ArchiveRemover}</b>，
 * 同步二者的 {@code maxHistory} 再立即 {@code clean(now)}，让新天数当场生效；启动时（{@link ApplicationReadyEvent}）
 * 再按持久化值重放一次，覆盖 yml 里的默认 7。<b>刻意仍只用 logback 这一个删除机制</b>——不自己再扫目录删文件，
 * 否则会和 logback 的 max-history 两套并存、互相打架（比如页面设 30 天、logback 仍按 7 天删）。开发机没有文件
 * appender（只打控制台）也没有日志目录，此时 {@code available=false}，页面把该控件置灰，读接口回默认 7。
 */
@Slf4j
@Service
public class LogService {

    /** 初次打开一个日志文件时，从末尾回溯读取的字节数（约等于「最近一屏多」的历史），避免把整个大文件灌进浏览器。 */
    private static final int INITIAL_BYTES = 256 * 1024;

    /** 单次 tail 最多读取的字节数；超出的部分留给下一次轮询接着读，保证响应体可控、追赶只是多轮几次。 */
    private static final int MAX_READ_BYTES = 512 * 1024;

    /** 日志保留天数默认值，与 application-prod.yml 里 {@code rollingpolicy.max-history} 的初始值一致。 */
    private static final int DEFAULT_RETENTION_DAYS = 7;

    /** 保留天数的合法区间；超出直接 400（前端 InputNumber 也用同一上下限置灰非法值）。 */
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 365;

    /** 超管设置的日志保留天数持久化在这个文件里（与日志同目录，.json 故不在可 tail 白名单内）。 */
    private static final String SETTINGS_FILE_NAME = "log-settings.json";

    private final ObjectMapper objectMapper;
    private final String logFileName;

    public LogService(ObjectMapper objectMapper, @Value("${logging.file.name:}") String logFileName) {
        this.objectMapper = objectMapper;
        this.logFileName = logFileName == null ? "" : logFileName.trim();
    }

    /** 日志目录 = 后端自身日志文件的父目录；未配置（开发机控制台输出）或父目录不存在时返回 null。 */
    private Path logDir() {
        if (logFileName.isEmpty()) {
            return null;
        }
        Path parent = Path.of(logFileName).toAbsolutePath().getParent();
        return parent != null && Files.isDirectory(parent) ? parent : null;
    }

    /**
     * 列出日志目录里的 {@code *.log} 常规文件（不含 .gz 归档），按最后修改时间倒序（最近在写的排最前）。
     * 目录不可用（开发机）或一个 .log 都没有时返回空数组——对前端就是「无可查看的日志文件」空态。
     */
    public ArrayNode listFiles() {
        ArrayNode result = objectMapper.createArrayNode();
        Path dir = logDir();
        if (dir == null) {
            return result;
        }
        List<Path> logs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                    .forEach(logs::add);
        } catch (Exception e) {
            log.warn("列举日志目录失败（按空列表处理）: {}", e.toString());
            return result;
        }
        logs.sort(Comparator.comparingLong(LogService::lastModified).reversed());
        for (Path p : logs) {
            ObjectNode node = result.addObject();
            node.put("name", p.getFileName().toString());
            node.put("size", size(p));
            node.put("lastModified", lastModified(p));
        }
        return result;
    }

    /**
     * 从 {@code offset} 字节处增量读取一个日志文件的新内容，只返回<b>完整的行</b>，并把 offset 前进到最后一个
     * 换行之后（半行留在文件里等下一轮，避免把正在写的一行截断、也避免多字节字符被从中间劈开）。
     *
     * <p>{@code offset < 0}（初次）或 {@code offset > 当前文件大小}（文件被轮转/截断）时，都退回到「读末尾
     * INITIAL_BYTES 块」并置 {@code rotated=true}，让前端清空重来。返回体：
     * {@code {file, offset(下次传入), size, rotated, lines[]}}。
     */
    public ObjectNode tail(String fileName, long offset) {
        Path dir = logDir();
        if (dir == null) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "当前运行环境未落日志文件，无法查看");
        }
        Path file = resolveSafe(dir, fileName);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("file", file.getFileName().toString());
        ArrayNode lines = result.putArray("lines");

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            boolean rotated = false;
            long start;
            if (offset < 0 || offset > size) {
                // 初次打开，或文件被轮转/截断（offset 落到文件之外）：回到读末尾一块。
                rotated = true;
                start = Math.max(0L, size - INITIAL_BYTES);
            } else {
                start = offset;
            }

            long available = size - start;
            if (available <= 0) {
                // 没有新内容：offset 原样停在文件末尾。
                result.put("offset", size);
                result.put("size", size);
                result.put("rotated", rotated);
                return result;
            }

            int toRead = (int) Math.min(available, MAX_READ_BYTES);
            byte[] buf = new byte[toRead];
            raf.seek(start);
            raf.readFully(buf);

            // 只在字节层面找换行：'\n'(0x0A) 绝不会出现在 UTF-8 多字节序列内部，故按字节切分是安全的。
            int contentFrom = 0;
            if (rotated && start > 0) {
                // 回溯到文件中间起点时，第一段是半行，丢掉直到第一个换行之后。
                int firstNl = indexOf(buf, (byte) '\n', 0, toRead);
                contentFrom = firstNl >= 0 ? firstNl + 1 : toRead; // 整块都没换行 = 全是半行，本轮不返回
            }
            int lastNl = lastIndexOf(buf, (byte) '\n', contentFrom, toRead);
            if (lastNl < contentFrom) {
                // 本轮读到的范围里没有任何完整行（只有一个还没写完的半行）：不前进 offset，等下一轮。
                result.put("offset", start);
                result.put("size", size);
                result.put("rotated", rotated);
                return result;
            }

            // 完整行区间 = buf[contentFrom .. lastNl]（含末尾换行）；offset 前进到 lastNl 之后。
            // 只按字节封顶（MAX_READ_BYTES），不再按行数截断——若在返回前 break 却把 offset 推到 lastNl，
            // 中间没返回的行会被永久跳过（丢日志）。行数交给前端自己的环形上限兜。
            String chunk = new String(buf, contentFrom, lastNl - contentFrom + 1, StandardCharsets.UTF_8);
            long newOffset = start + lastNl + 1;
            // chunk 以 '\n' 结尾，split(-1) 会在末尾多出一个空串，去掉它。
            String[] split = chunk.split("\n", -1);
            int last = split.length > 0 && split[split.length - 1].isEmpty() ? split.length - 1 : split.length;
            for (int i = 0; i < last; i++) {
                lines.add(split[i]);
            }

            result.put("offset", newOffset);
            result.put("size", size);
            result.put("rotated", rotated);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 可能正撞上 logback 滚动/写入的极短窗口；这一次读失败就抛 NOT_FOUND 让前端下一轮重试。
            log.debug("读取日志文件失败: {}", e.toString());
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "读取日志文件失败，请稍后重试");
        }
    }

    /**
     * 把一个「文件名」安全解析成日志目录内的真实路径。任何越界尝试（绝对路径、{@code ..}、目录分隔符、
     * 非 {@code .log} 后缀、规范化后逃出日志目录）都直接 400/404 拒绝。
     */
    private Path resolveSafe(Path dir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少日志文件名");
        }
        String name = fileName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.toLowerCase().endsWith(".log")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的日志文件名");
        }
        Path resolved = dir.resolve(name).normalize();
        if (!resolved.startsWith(dir.normalize()) || !Files.isRegularFile(resolved)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "日志文件不存在");
        }
        return resolved;
    }

    // ---------------------------------------------------------------------
    // 日志保留天数（方案 v44）：读 / 写设置文件 + 应用到 logback 的 max-history
    // ---------------------------------------------------------------------

    /**
     * 当前保留天数与「本环境是否可配置」。返回 {@code {retentionDays, available}}：
     * {@code available = 日志目录存在}（开发机只打控制台、无日志目录 → false，前端据此置灰控件）。
     * 读不到设置文件（还没设过 / 文件坏）一律退回默认 {@value #DEFAULT_RETENTION_DAYS} 天。
     */
    public ObjectNode getRetention() {
        boolean available = logDir() != null;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", readPersistedRetentionDays());
        result.put("available", available);
        return result;
    }

    /**
     * 设置日志保留天数：校验区间 → 原子持久化到 {@code logs/log-settings.json} → 立即应用到运行中的 logback。
     * 无日志目录（开发机）时无法持久化也无 appender 可应用，直接 400 拒绝，不做「假装成功」的静默降级。
     */
    public ObjectNode setRetention(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "保留天数需在 " + MIN_RETENTION_DAYS + " 到 " + MAX_RETENTION_DAYS + " 天之间");
        }
        Path dir = logDir();
        if (dir == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前运行环境未落日志文件，无法配置日志保留天数");
        }
        writeRetentionDays(dir, retentionDays);
        applyRetentionToLogback(retentionDays);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", retentionDays);
        result.put("available", true);
        return result;
    }

    /**
     * 启动就绪后按持久化值重放一次：yml 里的 {@code max-history: 7} 只是 logback 初始化时的默认，
     * 若超管此前在页面上改过天数（设置文件里有值），这里把它重新应用到运行中的 logback 并清一次旧归档，
     * 使设置在重启后依然生效。没有日志目录或没有设置文件时什么都不做（沿用 yml 默认）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void applyPersistedRetentionOnStartup() {
        if (logDir() == null || settingsFile() == null || !Files.isRegularFile(settingsFile())) {
            return;
        }
        int days = readPersistedRetentionDays();
        applyRetentionToLogback(days);
        log.info("已按持久化设置应用日志保留天数: {} 天", days);
    }

    /** 设置文件路径 = 日志目录下的 {@value #SETTINGS_FILE_NAME}；无日志目录时返回 null。 */
    private Path settingsFile() {
        Path dir = logDir();
        return dir == null ? null : dir.resolve(SETTINGS_FILE_NAME);
    }

    /** 读持久化的保留天数；文件缺失 / 解析失败 / 值越界，一律退回默认 {@value #DEFAULT_RETENTION_DAYS}。 */
    private int readPersistedRetentionDays() {
        Path file = settingsFile();
        if (file == null || !Files.isRegularFile(file)) {
            return DEFAULT_RETENTION_DAYS;
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            int days = node.path("retentionDays").asInt(DEFAULT_RETENTION_DAYS);
            return (days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS) ? days : DEFAULT_RETENTION_DAYS;
        } catch (Exception e) {
            // 可能正撞上原子写的极短窗口，或设置文件被写坏；退回默认，绝不让一个坏文件把接口打挂。
            log.debug("读取日志保留天数设置失败（按默认 {} 天处理）: {}", DEFAULT_RETENTION_DAYS, e.toString());
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /** 原子写设置文件（与 {@link DeployService} 写进度文件同一手法：先写 .tmp 再 ATOMIC_MOVE 覆盖）。 */
    private void writeRetentionDays(Path dir, int retentionDays) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("retentionDays", retentionDays);
            Path absolute = dir.resolve(SETTINGS_FILE_NAME).toAbsolutePath();
            Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // 写不进去就说明这次设置没法持久化（磁盘/权限问题），如实报错而不是假装成功。
            log.warn("写入日志保留天数设置失败: {}", e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "保存日志保留天数失败，请稍后重试");
        }
    }

    /**
     * 把保留天数应用到运行中的 logback：找到根 logger 上的 {@link RollingFileAppender}，
     * 同步其 {@link TimeBasedRollingPolicy} 与该策略私有的 {@link ArchiveRemover} 的 {@code maxHistory}，
     * 再立即 {@code clean(now)} 清掉已超期的归档。
     *
     * <p>{@code maxHistory} 在策略里是私有字段、无公开 getter，logback 又只在 {@code start()} 时把它拷给
     * archiveRemover，所以运行期光调 {@code policy.setMaxHistory} 不会传导到真正执行删除的 remover——
     * 这里用一次反射拿到该字段（logback 由 Spring Boot 3.5 锁定在 1.5.x，字段名稳定）。任何一步失败都只记
     * warn 不抛错：设置已持久化，最坏情况是下次重启时由 {@link #applyPersistedRetentionOnStartup()} 兜底生效。
     * 开发机没有文件 appender，循环自然空转、什么都不改。
     */
    private void applyRetentionToLogback(int retentionDays) {
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
                return;
            }
            ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders();
            while (appenders.hasNext()) {
                Appender<ILoggingEvent> appender = appenders.next();
                if (!(appender instanceof RollingFileAppender<ILoggingEvent> rolling)) {
                    continue;
                }
                RollingPolicy policy = rolling.getRollingPolicy();
                if (!(policy instanceof TimeBasedRollingPolicy<?> timeBased)) {
                    continue;
                }
                timeBased.setMaxHistory(retentionDays);
                Field field = TimeBasedRollingPolicy.class.getDeclaredField("archiveRemover");
                field.setAccessible(true);
                if (field.get(timeBased) instanceof ArchiveRemover remover) {
                    remover.setMaxHistory(retentionDays);
                    remover.clean(Instant.now());
                }
            }
        } catch (Exception e) {
            log.warn("应用日志保留天数到 logback 失败（已持久化，将在下次重启生效）: {}", e.toString());
        }
    }

    private static int indexOf(byte[] buf, byte target, int from, int to) {
        for (int i = from; i < to; i++) {
            if (buf[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] buf, byte target, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (buf[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}
