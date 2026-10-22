package com.familyhome.boot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 「数据库」页后端（方案 v52）：可视化浏览表 / 行内增删查改 / 结构化改表结构 / 跑任意 SQL / 每日备份 + 保留天数。
 *
 * <p><b>四件事一个页</b>（对应用户四条决策）：
 * <ol>
 *   <li><b>可视化 + 增删查改</b>：左侧列所有表，右侧分页表格展示数据，行内新增/编辑/删除。所有数据操作走后端
 *       自己的 JDBC 连接（{@link DataSource}），<b>标识符（表名/列名）逐个正则校验 + 反引号包裹，值一律用
 *       PreparedStatement 占位符</b>——这条路是"结构化"的，杜绝注入。</li>
 *   <li><b>改表结构 = 结构化表单改列</b>：加列/改列/删列由前端表单收集字段名、类型、可空、默认值、注释，
 *       后端翻译成 {@code ALTER TABLE}。类型/默认值是自由文本，做轻量消毒（拒分号、反引号、注释符）。</li>
 *   <li><b>跑任意 SQL（全放开）</b>：{@link #runSql} 用 {@code Statement.execute} 直接跑，SELECT 类回结果集、
 *       写/DDL 类回影响行数。<b>这条路刻意不做任何语句白名单</b>（用户明确要"读+写+DDL 全放开"），风险由前端
 *       二次确认弹窗 + 仅超管门禁兜住；能跑任意 SQL 的人本就能跑任意 DDL，故 DDL 表单只是便利、不是安全边界。</li>
 *   <li><b>每日备份 + 保留天数</b>：{@code mysqldump} 导出整库到 {@code <install>/backups}（logs 同级目录），
 *       每日 04:00 定时跑一次（{@link #scheduledBackup}），也可页面「立即备份」。保留天数持久化在
 *       {@code backups/db-settings.json}（原子写），默认 7、区间 1..365，启动与每次备份后按天数清理旧 .sql。</li>
 * </ol>
 *
 * <p><b>备份目录锚定 = {@code logging.file.name} 的祖父目录 / backups</b>（与 {@link LogService} 锚定日志目录同一手法）：
 * 部署脚本注入 {@code LOGGING_FILE_NAME=<install>/logs/server.log}，故备份自动落到 {@code <install>/backups}，无需再配目录。
 * 开发机没有 {@code logging.file.name} → 备份目录为 null → 备份能力优雅退化（{@code available=false}），但浏览/改数据/
 * 改结构/跑 SQL 照常（它们只依赖 JDBC 连接，与 mysqldump 无关）。
 *
 * <p><b>mysqldump 定位</b>：{@code fh.database.mysqldump-path} 非空则用它（部署机 Windows 在 {@code <mysql>/bin}），
 * 留空按 PATH 找 {@code mysqldump}（开发机 brew、unix 系统包都在 PATH）。凭据从 {@code spring.datasource.*} 读，
 * 写进一个 0600 的临时 {@code --defaults-extra-file}（绝不把口令拼进命令行，避免进程列表泄露）。
 *
 * <p><b>无主键的表</b>不给行内编辑（改/删无从定位唯一行），{@link #getTableData} 会标 {@code editable=false}，
 * 前端据此隐藏行操作、提示改用 SQL 查询页。
 */
@Slf4j
@Service
public class DatabaseService {

    /** 表名/列名等标识符的合法字符（本库全是 ASCII 下划线命名）；不符直接拒，杜绝把标识符当注入点。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    /** SQL 查询页单次返回的最大行数：够看够排错，又不至于把一张大表整张灌进浏览器。超出置 truncated=true。 */
    private static final int MAX_SQL_ROWS = 1000;

    /** 可视化表格单页最大行数（前端 pageSize 上限，防手填一个巨值把后端拖垮）。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 保留天数默认值 / 合法区间（与日志页、操作审计页同一口径）。 */
    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 365;

    /** 备份保留天数持久化文件（在 backups 目录内，.json 故不会被当成 .sql 备份列出/清理）。 */
    private static final String SETTINGS_FILE_NAME = "db-settings.json";

    /** 备份文件名时间戳格式。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** mysqldump 超时（秒）：整库导出，留足余量。 */
    private static final long DUMP_TIMEOUT_SECONDS = 600;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String logFileName;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String mysqldumpPathConfig;

    public DatabaseService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            @Value("${logging.file.name:}") String logFileName,
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String dbUser,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${fh.database.mysqldump-path:}") String mysqldumpPathConfig) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.logFileName = logFileName == null ? "" : logFileName.trim();
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        this.dbUser = dbUser == null ? "" : dbUser;
        this.dbPassword = dbPassword == null ? "" : dbPassword;
        this.mysqldumpPathConfig = mysqldumpPathConfig == null ? "" : mysqldumpPathConfig.trim();
    }

    // =====================================================================
    // 表清单 / 结构
    // =====================================================================

    /**
     * 列出当前库的所有表（名 + 行数估算 + 注释）。返回 {@code {database, tables:[{name, rows, comment}]}}。
     * 行数取自 {@code information_schema.tables.table_rows}（InnoDB 是估算值，够列表展示用）。
     */
    public ObjectNode listTables() {
        ObjectNode result = objectMapper.createObjectNode();
        try (Connection conn = dataSource.getConnection()) {
            String database = currentDatabase(conn);
            result.put("database", database);
            ArrayNode tables = result.putArray("tables");
            String sql = "SELECT table_name, table_rows, table_comment FROM information_schema.tables "
                    + "WHERE table_schema = ? ORDER BY table_name";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode t = tables.addObject();
                        t.put("name", rs.getString("table_name"));
                        t.put("rows", rs.getLong("table_rows"));
                        String comment = rs.getString("table_comment");
                        t.put("comment", comment == null ? "" : comment);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("列举数据库表失败: {}", e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "读取表清单失败：" + rootMessage(e));
        }
        return result;
    }

    /**
     * 某张表的结构（列定义 + 主键列）。返回
     * {@code {table, columns:[{name, type, nullable, key, defaultValue, extra, comment, primaryKey}], primaryKeys:[...]}}。
     * 供「表结构」Tab 渲染，也是行内编辑定位主键、结构化改列的依据。
     */
    public ObjectNode getColumns(String table) {
        String quoted = ident(table, "表名");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("table", table);
        ArrayNode columns = result.putArray("columns");
        try (Connection conn = dataSource.getConnection()) {
            Set<String> pks = primaryKeyColumns(conn, table);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW FULL COLUMNS FROM " + quoted)) {
                while (rs.next()) {
                    ObjectNode c = columns.addObject();
                    String field = rs.getString("Field");
                    c.put("name", field);
                    c.put("type", rs.getString("Type"));
                    c.put("nullable", "YES".equalsIgnoreCase(rs.getString("Null")));
                    c.put("key", nullToEmpty(rs.getString("Key")));
                    String def = rs.getString("Default");
                    c.put("defaultValue", def);
                    c.put("extra", nullToEmpty(rs.getString("Extra")));
                    c.put("comment", nullToEmpty(rs.getString("Comment")));
                    c.put("primaryKey", pks.contains(field));
                }
            }
            ArrayNode pkArray = result.putArray("primaryKeys");
            pks.forEach(pkArray::add);
        } catch (SQLException e) {
            log.warn("读取表结构失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "读取表结构失败：" + rootMessage(e));
        }
        return result;
    }

    /** 某表的主键列名（有序）；无主键返回空集。 */
    private Set<String> primaryKeyColumns(Connection conn, String table) throws SQLException {
        Set<String> pks = new LinkedHashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(conn.getCatalog(), null, table)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    /**
     * 某张表的完整索引清单（方案 v56）。列结构表格里的「键」列只是 {@code SHOW FULL COLUMNS} 的简略标记
     * （PRI/UNI/MUL），看不出索引名、复合索引的列组合与顺序、索引类型；这里读 {@code SHOW INDEX} 按
     * {@code Key_name} 聚合成「一个索引一行」。返回
     * {@code {table, indexes:[{name, unique, type, columns:[有序列名], comment, visible}]}}。
     *
     * <p>列顺序按 {@code Seq_in_index}（复合索引的列序就是它的语义）；前缀索引（{@code Sub_part} 非空）标成
     * {@code col(N)}；函数索引（{@code Column_name} 为空、{@code Expression} 有值）直接展示表达式。
     * 纯只读，不改任何结构。索引出现顺序沿用 {@code SHOW INDEX} 的返回序（PRIMARY 通常在最前）。
     */
    public ObjectNode getIndexes(String table) {
        String quoted = ident(table, "表名");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("table", table);
        ArrayNode indexes = result.putArray("indexes");
        // Key_name -> 该索引的聚合节点；用 LinkedHashMap 保留 SHOW INDEX 的返回顺序。
        java.util.Map<String, ObjectNode> byName = new java.util.LinkedHashMap<>();
        // Key_name -> 按 Seq_in_index 排好序的列展示串。
        java.util.Map<String, List<String[]>> colsByName = new java.util.LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW INDEX FROM " + quoted)) {
            while (rs.next()) {
                String keyName = nullToEmpty(rs.getString("Key_name"));
                ObjectNode idx = byName.get(keyName);
                if (idx == null) {
                    idx = objectMapper.createObjectNode();
                    idx.put("name", keyName);
                    idx.put("unique", rs.getInt("Non_unique") == 0);
                    idx.put("type", nullToEmpty(rs.getString("Index_type")));
                    idx.put("comment", nullToEmpty(rs.getString("Index_comment")));
                    idx.put("visible", !"NO".equalsIgnoreCase(nullToEmpty(rs.getString("Visible"))));
                    byName.put(keyName, idx);
                    colsByName.put(keyName, new ArrayList<>());
                }
                String column = nullToEmpty(rs.getString("Column_name"));
                if (column.isEmpty()) {
                    // 函数索引：Column_name 为空，展示 Expression。
                    column = nullToEmpty(rs.getString("Expression"));
                }
                int subPart = rs.getInt("Sub_part");
                String display = (!rs.wasNull() && subPart > 0) ? column + "(" + subPart + ")" : column;
                colsByName.get(keyName).add(new String[]{String.valueOf(rs.getInt("Seq_in_index")), display});
            }
        } catch (SQLException e) {
            log.warn("读取表索引失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "读取表索引失败：" + rootMessage(e));
        }
        byName.forEach((name, idx) -> {
            List<String[]> parts = colsByName.get(name);
            parts.sort(Comparator.comparingInt(a -> parseIntSafe(a[0])));
            ArrayNode colArray = idx.putArray("columns");
            parts.forEach(p -> colArray.add(p[1]));
            indexes.add(idx);
        });
        return result;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // =====================================================================
    // 数据浏览（分页）
    // =====================================================================

    /**
     * 分页读取一张表的数据。返回
     * {@code {table, columns:[{name, type, primaryKey}], rows:[{col:val}], total, page, size, editable}}。
     * {@code editable=false} 表示该表无主键、不给行内改/删（无从定位唯一行）。单元格值序列化成 JSON 安全形式
     * （二进制转占位文本、时间转字符串），前端按字符串展示与回填。
     */
    public ObjectNode getTableData(String table, int page, int size) {
        String quoted = ident(table, "表名");
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("table", table);
        result.put("page", p);
        result.put("size", s);
        try (Connection conn = dataSource.getConnection()) {
            Set<String> pks = primaryKeyColumns(conn, table);
            result.put("editable", !pks.isEmpty());

            long total = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quoted)) {
                if (rs.next()) {
                    total = rs.getLong(1);
                }
            }
            result.put("total", total);

            ArrayNode rows = result.putArray("rows");
            ArrayNode columns = result.putArray("columns");
            long offset = (long) (p - 1) * s;
            String sql = "SELECT * FROM " + quoted + " LIMIT " + s + " OFFSET " + offset;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                for (int i = 1; i <= n; i++) {
                    ObjectNode c = columns.addObject();
                    String name = md.getColumnLabel(i);
                    c.put("name", name);
                    c.put("type", md.getColumnTypeName(i));
                    c.put("primaryKey", pks.contains(name));
                }
                while (rs.next()) {
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= n; i++) {
                        row.set(md.getColumnLabel(i), jsonValue(rs.getObject(i)));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("读取表数据失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "读取表数据失败：" + rootMessage(e));
        }
        return result;
    }

    // =====================================================================
    // 行内增删改（结构化、占位符绑值）
    // =====================================================================

    /**
     * 新增一行。{@code values} 是 {列名: JSON值}；只 INSERT 提供的列，其余走库默认值。返回 {@code {affected}}。
     * 列名逐个校验 + 反引号，值用占位符绑定。
     */
    public ObjectNode insertRow(String table, JsonNode values) {
        String quoted = ident(table, "表名");
        if (values == null || !values.isObject() || values.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "没有要写入的字段");
        }
        List<String> cols = new ArrayList<>();
        List<JsonNode> vals = new ArrayList<>();
        values.fields().forEachRemaining(e -> {
            cols.add(ident(e.getKey(), "列名"));
            vals.add(e.getValue());
        });
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quoted).append(" (");
        sql.append(String.join(", ", cols));
        sql.append(") VALUES (");
        sql.append(joinPlaceholders(cols.size()));
        sql.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < vals.size(); i++) {
                bind(ps, i + 1, vals.get(i));
            }
            int affected = ps.executeUpdate();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("affected", affected);
            return result;
        } catch (SQLException e) {
            log.warn("新增行失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "新增失败：" + rootMessage(e));
        }
    }

    /**
     * 改一行。{@code pk} 定位唯一行（主键列 → 值），{@code values} 是要改的列。两者都校验标识符 + 占位符绑值。
     * 表无主键或 pk 为空一律拒（无从安全定位）。返回 {@code {affected}}。
     */
    public ObjectNode updateRow(String table, JsonNode pk, JsonNode values) {
        String quoted = ident(table, "表名");
        requirePk(pk);
        if (values == null || !values.isObject() || values.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "没有要修改的字段");
        }
        List<String> setCols = new ArrayList<>();
        List<JsonNode> setVals = new ArrayList<>();
        values.fields().forEachRemaining(e -> {
            setCols.add(ident(e.getKey(), "列名"));
            setVals.add(e.getValue());
        });
        List<String> whereCols = new ArrayList<>();
        List<JsonNode> whereVals = new ArrayList<>();
        pk.fields().forEachRemaining(e -> {
            whereCols.add(ident(e.getKey(), "列名"));
            whereVals.add(e.getValue());
        });
        StringBuilder sql = new StringBuilder("UPDATE ").append(quoted).append(" SET ");
        for (int i = 0; i < setCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(setCols.get(i)).append(" = ?");
        }
        sql.append(" WHERE ");
        for (int i = 0; i < whereCols.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(whereCols.get(i)).append(" = ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (JsonNode v : setVals) bind(ps, idx++, v);
            for (JsonNode v : whereVals) bind(ps, idx++, v);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "没有匹配到要修改的行（可能已被删除）");
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.put("affected", affected);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (SQLException e) {
            log.warn("修改行失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "修改失败：" + rootMessage(e));
        }
    }

    /** 删一行，{@code pk} 定位。表无主键或 pk 为空一律拒。返回 {@code {affected}}。 */
    public ObjectNode deleteRow(String table, JsonNode pk) {
        String quoted = ident(table, "表名");
        requirePk(pk);
        List<String> whereCols = new ArrayList<>();
        List<JsonNode> whereVals = new ArrayList<>();
        pk.fields().forEachRemaining(e -> {
            whereCols.add(ident(e.getKey(), "列名"));
            whereVals.add(e.getValue());
        });
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quoted).append(" WHERE ");
        for (int i = 0; i < whereCols.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(whereCols.get(i)).append(" = ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (JsonNode v : whereVals) bind(ps, idx++, v);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "没有匹配到要删除的行（可能已被删除）");
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.put("affected", affected);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (SQLException e) {
            log.warn("删除行失败 {}: {}", table, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "删除失败：" + rootMessage(e));
        }
    }

    private void requirePk(JsonNode pk) {
        if (pk == null || !pk.isObject() || pk.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该表没有主键，无法用可视化行编辑，请到 SQL 查询页操作");
        }
    }

    // =====================================================================
    // 任意 SQL（全放开）
    // =====================================================================

    /**
     * 跑一条任意 SQL（用户明确要"读+写+DDL 全放开"）。{@code Statement.execute} 返回 true 表示有结果集
     * （SELECT/SHOW/DESCRIBE/EXPLAIN），据此回 {@code {type:'resultSet', columns, rows, truncated}}；否则回
     * {@code {type:'update', updateCount}}。<b>不做语句白名单</b>——风险由前端二次确认 + 仅超管门禁兜住。
     * 结果集最多回 {@value #MAX_SQL_ROWS} 行，超出置 {@code truncated=true}。SQL 报错时把数据库原始信息如实回吐。
     */
    public ObjectNode runSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "SQL 不能为空");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sql", sql.trim());
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            boolean hasResultSet = st.execute(sql.trim());
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    result.put("type", "resultSet");
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    ArrayNode columns = result.putArray("columns");
                    for (int i = 1; i <= n; i++) {
                        ObjectNode c = columns.addObject();
                        c.put("name", md.getColumnLabel(i));
                        c.put("type", md.getColumnTypeName(i));
                    }
                    ArrayNode rows = result.putArray("rows");
                    int count = 0;
                    boolean truncated = false;
                    while (rs.next()) {
                        if (count >= MAX_SQL_ROWS) {
                            truncated = true;
                            break;
                        }
                        ObjectNode row = rows.addObject();
                        for (int i = 1; i <= n; i++) {
                            row.set(md.getColumnLabel(i), jsonValue(rs.getObject(i)));
                        }
                        count++;
                    }
                    result.put("rowCount", count);
                    result.put("truncated", truncated);
                }
            } else {
                result.put("type", "update");
                result.put("updateCount", st.getUpdateCount());
            }
            return result;
        } catch (SQLException e) {
            log.warn("执行 SQL 失败: {}", e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "SQL 执行失败：" + rootMessage(e));
        }
    }

    // =====================================================================
    // 结构化改表结构（加列 / 改列 / 删列 → ALTER TABLE）
    // =====================================================================

    /**
     * 结构化改表结构。{@code op} 形如 {@code {action:'add'|'modify'|'drop'|'comment', name, dataType, nullable,
     * defaultValue, comment, after}}。后端翻译成一条 {@code ALTER TABLE} 执行。字段名走标识符校验；类型/默认值/
     * 注释是自由文本，做轻量消毒（拒分号、反引号、注释符）。返回 {@code {ddl, affected}}（affected 恒为 0，DDL 无行数）。
     *
     * <p>{@code action='comment'} 改的是<b>表级说明</b>（MySQL 表注释）：{@code ALTER TABLE x COMMENT = '...'}，
     * 与列无关故不需要 {@code name}；{@code comment} 传空串即清空说明。
     */
    public ObjectNode alterTable(String table, JsonNode op) {
        String quoted = ident(table, "表名");
        if (op == null || !op.isObject()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少改结构参数");
        }
        String action = op.path("action").asText("");
        String ddl;
        switch (action) {
            case "comment" -> {
                // 表级说明：值走 quoteLiteral 单引号转义（与列注释同一手法），空串清空说明。
                String comment = op.path("comment").asText("");
                ddl = "ALTER TABLE " + quoted + " COMMENT = " + quoteLiteral(comment);
            }
            case "add" -> {
                String colQuoted = ident(op.path("name").asText(""), "字段名");
                String type = sanitizeType(op.path("dataType").asText(""));
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quoted)
                        .append(" ADD COLUMN ").append(colQuoted).append(" ").append(type);
                appendColumnAttrs(sb, op);
                String after = op.path("after").asText("");
                if (!after.isBlank()) {
                    sb.append(" AFTER ").append(ident(after, "位置字段名"));
                }
                ddl = sb.toString();
            }
            case "modify" -> {
                String colQuoted = ident(op.path("name").asText(""), "字段名");
                String type = sanitizeType(op.path("dataType").asText(""));
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quoted)
                        .append(" MODIFY COLUMN ").append(colQuoted).append(" ").append(type);
                appendColumnAttrs(sb, op);
                ddl = sb.toString();
            }
            case "drop" -> {
                String colQuoted = ident(op.path("name").asText(""), "字段名");
                ddl = "ALTER TABLE " + quoted + " DROP COLUMN " + colQuoted;
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST, "未知的改结构操作：" + action);
        }
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(ddl);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("ddl", ddl);
            result.put("affected", 0);
            return result;
        } catch (SQLException e) {
            log.warn("改表结构失败 {}: {}", ddl, e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "改表结构失败：" + rootMessage(e));
        }
    }

    /** 拼 {@code [NOT] NULL / DEFAULT / COMMENT} 三段（add 与 modify 共用）。 */
    private void appendColumnAttrs(StringBuilder sb, JsonNode op) {
        boolean nullable = op.path("nullable").asBoolean(true);
        sb.append(nullable ? " NULL" : " NOT NULL");
        String def = defaultLiteral(op.path("defaultValue"));
        if (def != null) {
            sb.append(" DEFAULT ").append(def);
        }
        String comment = op.path("comment").asText("");
        if (!comment.isBlank()) {
            sb.append(" COMMENT ").append(quoteLiteral(comment));
        }
    }

    /**
     * 默认值转 SQL 字面量：空 → 不出 DEFAULT 段；NULL / CURRENT_TIMESTAMP 原样；纯数字原样；其余按字符串字面量转义。
     */
    private String defaultLiteral(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.equalsIgnoreCase("NULL")) {
            return "NULL";
        }
        if (raw.equalsIgnoreCase("CURRENT_TIMESTAMP")) {
            return "CURRENT_TIMESTAMP";
        }
        if (raw.matches("^-?\\d+(\\.\\d+)?$")) {
            return raw;
        }
        return quoteLiteral(raw);
    }

    /** 字符串字面量：单引号包裹，内部的 {@code '} 与 {@code \} 转义。 */
    private String quoteLiteral(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** 字段类型消毒：拒分号、反引号、行/块注释符，限长；类型本身是自由文本（VARCHAR(255)/DECIMAL(10,2)/ENUM(...)）。 */
    private String sanitizeType(String type) {
        if (type == null || type.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少字段类型");
        }
        String t = type.trim();
        if (t.length() > 120 || t.contains(";") || t.contains("`") || t.contains("--") || t.contains("/*")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的字段类型");
        }
        return t;
    }

    // =====================================================================
    // 备份：立即备份 / 列表 / 删除 / 每日定时
    // =====================================================================

    /** 备份目录 = {@code logging.file.name} 祖父目录 / backups（logs 同级）；未落日志文件（开发机）时为 null。 */
    private Path backupDir() {
        if (logFileName.isEmpty()) {
            return null;
        }
        Path logs = Path.of(logFileName).toAbsolutePath().getParent();
        if (logs == null) {
            return null;
        }
        Path install = logs.getParent();
        return install == null ? null : install.resolve("backups");
    }

    /** 备份能力是否可用（有备份目录即可；mysqldump 缺失在真正备份时给出明确报错）。 */
    public boolean backupAvailable() {
        return backupDir() != null;
    }

    /**
     * 立即备份一次：{@code mysqldump} 整库导出到 {@code backups/<db>_<时间戳>.sql}，成功后按保留天数清理旧备份。
     * 返回 {@code {available, name, size, time}}。无备份目录（开发机）直接 400，不假装成功。
     */
    public ObjectNode backupNow() {
        Path dir = backupDir();
        if (dir == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前运行环境未落日志文件，无法确定备份目录，备份不可用");
        }
        String[] hpd = parseJdbcUrl(jdbcUrl);
        String database = hpd[2];
        if (database.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "无法从数据库连接串解析库名，备份失败");
        }
        String mysqldump = resolveMysqldump();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "创建备份目录失败：" + e.getMessage());
        }
        String name = database + "_" + LocalDateTime.now().format(STAMP) + ".sql";
        Path out = dir.resolve(name);
        Path ini = null;
        try {
            ini = writeDefaultsFile(hpd[0], hpd[1], database);
            List<String> cmd = new ArrayList<>();
            cmd.add(mysqldump);
            cmd.add("--defaults-extra-file=" + ini.toAbsolutePath());
            cmd.add("--single-transaction");
            cmd.add("--quick");
            cmd.add("--default-character-set=utf8mb4");
            cmd.add(database);
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectOutput(out.toFile());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String stderr;
            try (Stream<String> lines = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)).lines()) {
                stderr = lines.reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }
            boolean exited = process.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                deleteQuietly(out);
                throw BizException.of(ErrorCode.BAD_REQUEST, "备份超时（超过 " + DUMP_TIMEOUT_SECONDS + " 秒），已中止");
            }
            if (process.exitValue() != 0) {
                deleteQuietly(out);
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        "mysqldump 失败（退出码 " + process.exitValue() + "）："
                                + (stderr.isBlank() ? "请确认部署机已安装 mysqldump 且凭据正确" : stderr.trim()));
            }
            applyRetention();
            ObjectNode result = objectMapper.createObjectNode();
            result.put("available", true);
            result.put("name", name);
            result.put("size", size(out));
            result.put("time", System.currentTimeMillis());
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            deleteQuietly(out);
            log.warn("备份失败: {}", e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "备份失败：" + e.getMessage());
        } finally {
            if (ini != null) {
                deleteQuietly(ini);
            }
        }
    }

    /**
     * 列出备份目录里的 {@code *.sql}（按修改时间倒序）+ 当前保留天数 + 是否可用。返回
     * {@code {available, backupDir, retentionDays, backups:[{name, size, lastModified}]}}。无备份目录时 available=false、空列表。
     */
    public ObjectNode listBackups() {
        ObjectNode result = objectMapper.createObjectNode();
        Path dir = backupDir();
        result.put("retentionDays", readPersistedRetentionDays());
        if (dir == null) {
            result.put("available", false);
            result.put("backupDir", "");
            result.putArray("backups");
            return result;
        }
        result.put("available", true);
        result.put("backupDir", dir.toString());
        ArrayNode backups = result.putArray("backups");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .forEach(files::add);
        } catch (Exception e) {
            // 目录还没建（从没备份过）：当成空列表，不是错误。
            return result;
        }
        files.sort(Comparator.comparingLong(DatabaseService::lastModified).reversed());
        for (Path p : files) {
            ObjectNode node = backups.addObject();
            node.put("name", p.getFileName().toString());
            node.put("size", size(p));
            node.put("lastModified", lastModified(p));
        }
        return result;
    }

    /** 删除一个备份文件（名字必须落在备份目录内、以 .sql 结尾）。返回 {@code {deleted}}。 */
    public ObjectNode deleteBackup(String name) {
        Path dir = backupDir();
        if (dir == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前运行环境未接入备份目录，无法删除");
        }
        Path file = resolveBackupSafe(dir, name);
        boolean deleted = deleteQuietly(file);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("deleted", deleted);
        return result;
    }

    /** 每日 04:00 自动备份一次（在操作审计 03:30 清理之后）；备份不可用（开发机）或失败都只记日志，绝不打挂应用。 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void scheduledBackup() {
        if (!backupAvailable()) {
            return;
        }
        try {
            ObjectNode r = backupNow();
            log.info("每日数据库备份完成: {} ({} 字节)", r.path("name").asText(), r.path("size").asLong());
        } catch (Exception e) {
            log.warn("每日数据库备份失败: {}", e.toString());
        }
    }

    /** 启动就绪后按持久化的保留天数清理一次旧备份（跨重启/跨天停机也能补清）。无备份目录时什么都不做。 */
    @EventListener(ApplicationReadyEvent.class)
    public void applyPersistedRetentionOnStartup() {
        if (backupDir() == null) {
            return;
        }
        int removed = applyRetention();
        if (removed > 0) {
            log.info("启动清理过期数据库备份 {} 个（保留 {} 天）", removed, readPersistedRetentionDays());
        }
    }

    // =====================================================================
    // 备份保留天数（读/写设置文件 + 清理）
    // =====================================================================

    /** 当前保留天数与「本环境是否可备份」。返回 {@code {retentionDays, available}}。 */
    public ObjectNode getSettings() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", readPersistedRetentionDays());
        result.put("available", backupDir() != null);
        return result;
    }

    /** 设置备份保留天数：校验区间 → 原子持久化到 {@code backups/db-settings.json} → 立即清理一次旧备份。 */
    public ObjectNode setSettings(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "保留天数需在 " + MIN_RETENTION_DAYS + " 到 " + MAX_RETENTION_DAYS + " 天之间");
        }
        Path dir = backupDir();
        if (dir == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前运行环境未接入备份目录，无法配置备份保留天数");
        }
        writeRetentionDays(dir, retentionDays);
        applyRetention();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("retentionDays", retentionDays);
        result.put("available", true);
        return result;
    }

    /** 删掉备份目录里修改时间早于「今天 - 保留天数」的 .sql。返回删除个数。无目录/读失败一律 0，不抛。 */
    private int applyRetention() {
        Path dir = backupDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int days = readPersistedRetentionDays();
        long cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int removed = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> old = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .filter(p -> lastModified(p) < cutoff)
                    .toList();
            for (Path p : old) {
                if (deleteQuietly(p)) {
                    removed++;
                }
            }
        } catch (Exception e) {
            log.warn("清理过期备份失败: {}", e.toString());
        }
        return removed;
    }

    /** 读持久化保留天数；文件缺失/坏/越界一律退回默认 {@value #DEFAULT_RETENTION_DAYS}。 */
    private int readPersistedRetentionDays() {
        Path dir = backupDir();
        if (dir == null) {
            return DEFAULT_RETENTION_DAYS;
        }
        Path file = dir.resolve(SETTINGS_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return DEFAULT_RETENTION_DAYS;
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            int days = node.path("retentionDays").asInt(DEFAULT_RETENTION_DAYS);
            return (days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS) ? days : DEFAULT_RETENTION_DAYS;
        } catch (Exception e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /** 原子写设置文件（先写 .tmp 再 ATOMIC_MOVE，与 LogService/DeployService 同一手法）。 */
    private void writeRetentionDays(Path dir, int retentionDays) {
        try {
            Files.createDirectories(dir);
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
            log.warn("写入备份保留天数设置失败: {}", e.toString());
            throw BizException.of(ErrorCode.BAD_REQUEST, "保存备份保留天数失败，请稍后重试");
        }
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    /** 解析 {@code jdbc:mysql://host:port/db?params} → {host, port, db}。解析不出时给本地默认值。 */
    private String[] parseJdbcUrl(String url) {
        String host = "127.0.0.1";
        String port = "3306";
        String db = "";
        try {
            String s = url;
            int scheme = s.indexOf("://");
            if (scheme >= 0) {
                s = s.substring(scheme + 3);
            }
            int slash = s.indexOf('/');
            String hostPort = slash >= 0 ? s.substring(0, slash) : s;
            String rest = slash >= 0 ? s.substring(slash + 1) : "";
            int q = rest.indexOf('?');
            db = q >= 0 ? rest.substring(0, q) : rest;
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon + 1);
            } else if (!hostPort.isBlank()) {
                host = hostPort;
            }
        } catch (Exception e) {
            log.warn("解析数据库连接串失败，用本地默认值: {}", e.toString());
        }
        return new String[]{host, port, db};
    }

    /** mysqldump 可执行文件：配了绝对路径且存在则用它，否则退回 PATH 上的 "mysqldump"。 */
    private String resolveMysqldump() {
        if (!mysqldumpPathConfig.isEmpty()) {
            Path p = Path.of(mysqldumpPathConfig);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
            log.warn("配置的 mysqldump 路径不存在，退回 PATH: {}", mysqldumpPathConfig);
        }
        return "mysqldump";
    }

    /**
     * 写一个仅供本次 mysqldump 用的 {@code --defaults-extra-file}（0600），装连接凭据。
     * 绝不把口令拼进命令行——命令行在进程列表里对所有本地用户可见，选项文件不是。用完即删（finally）。
     */
    private Path writeDefaultsFile(String host, String port, String database) throws Exception {
        Path ini = Files.createTempFile("fh-dump-", ".cnf");
        StringBuilder sb = new StringBuilder("[client]\n");
        sb.append("user=").append(iniValue(dbUser)).append("\n");
        sb.append("password=").append(iniValue(dbPassword)).append("\n");
        sb.append("host=").append(iniValue(host)).append("\n");
        sb.append("port=").append(iniValue(port)).append("\n");
        Files.writeString(ini, sb.toString(), StandardCharsets.UTF_8);
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms = new LinkedHashSet<>();
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(ini, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限位；临时文件已在系统临时目录，可接受。
        }
        return ini;
    }

    /** ini 值用双引号包裹并转义内部的 {@code \} 与 {@code "}（MySQL 选项文件语法）。 */
    private String iniValue(String v) {
        return "\"" + (v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    /** 备份文件名安全解析：拒路径分隔符与 ..，必须 .sql 结尾、落在备份目录内、是真实文件。 */
    private Path resolveBackupSafe(Path dir, String name) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少备份文件名");
        }
        String n = name.trim();
        if (n.contains("/") || n.contains("\\") || n.contains("..")
                || !n.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的备份文件名");
        }
        Path resolved = dir.resolve(n).normalize();
        if (!resolved.startsWith(dir.normalize()) || !Files.isRegularFile(resolved)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "备份文件不存在");
        }
        return resolved;
    }

    /** 标识符校验 + 反引号包裹。 */
    private String ident(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "非法的" + what + "：" + name);
        }
        return "`" + name + "`";
    }

    /** n 个占位符逗号拼接。 */
    private String joinPlaceholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        return sb.toString();
    }

    /** 把一个 JSON 值绑到 PreparedStatement 的第 i 个占位符（null/布尔/整数/小数/字符串分别处理）。 */
    private void bind(PreparedStatement ps, int i, JsonNode v) throws SQLException {
        if (v == null || v.isNull()) {
            ps.setObject(i, null);
        } else if (v.isBoolean()) {
            ps.setBoolean(i, v.booleanValue());
        } else if (v.isIntegralNumber()) {
            ps.setLong(i, v.longValue());
        } else if (v.isNumber()) {
            ps.setDouble(i, v.doubleValue());
        } else {
            ps.setString(i, v.asText());
        }
    }

    /** 把一个 JDBC 列值转成 JSON 安全节点：null→NullNode，二进制→占位文本，数字/布尔原样，其余转字符串。 */
    private JsonNode jsonValue(Object v) {
        if (v == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if (v instanceof byte[] bytes) {
            return objectMapper.getNodeFactory().textNode("[二进制 " + bytes.length + " 字节]");
        }
        if (v instanceof Boolean b) {
            return objectMapper.getNodeFactory().booleanNode(b);
        }
        if (v instanceof Number n) {
            if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
                return objectMapper.getNodeFactory().numberNode(n.longValue());
            }
            return objectMapper.getNodeFactory().numberNode(n.doubleValue());
        }
        return objectMapper.getNodeFactory().textNode(String.valueOf(v));
    }

    private String currentDatabase(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? nullToEmpty(rs.getString(1)) : "";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 取 SQLException 链里最内层那句人类可读的信息（MySQL 的报错通常在最内层）。 */
    private static String rootMessage(SQLException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? e.toString() : msg;
    }

    private static boolean deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (Exception e) {
            return false;
        }
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
