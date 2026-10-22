package com.familyhome.boot.controller.b;

import com.fasterxml.jackson.databind.JsonNode;
import com.familyhome.boot.service.DatabaseService;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「数据库」接口（方案 v52）：可视化浏览表 / 行内增删查改 / 结构化改表结构 / 跑任意 SQL / 每日备份 + 保留天数。
 *
 * <p>与系统后台其它高危页同一道门槛——每个方法第一行都 {@link CurrentUserHolder#requirePermission(String)} 判
 * {@code database.access}（默认只有超管勾了它）。这一页能跑任意 SQL、能改表结构、能删数据，是全系统权限最高的页，
 * 藏菜单只是体验，绕开 UI 直接敲接口照样 403。
 *
 * <p>端点分五组：
 * <ul>
 *   <li>浏览：{@code GET /tables}（表清单）、{@code GET /table/columns}（结构）、{@code GET /table/indexes}（索引清单）、{@code GET /table/data}（分页数据）。</li>
 *   <li>行内增删改：{@code POST /table/row}（新增）、{@code PUT /table/row}（改）、{@code POST /table/row/delete}（删）。
 *       改/删靠主键定位，无主键的表后端直接拒。</li>
 *   <li>改表结构：{@code POST /table/alter}（加列/改列/删列/改表说明，结构化参数翻译成 ALTER）。</li>
 *   <li>任意 SQL：{@code POST /sql}（全放开，SELECT 回结果集、写/DDL 回影响行数）。</li>
 *   <li>备份：{@code GET /backups}（列表）、{@code POST /backup}（立即备份）、{@code POST /backup/delete}（删一个）、
 *       {@code GET/PUT /settings}（保留天数）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/b/database")
public class DatabaseController {

    private final DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /** 表清单（名 + 行数估算 + 注释）。 */
    @GetMapping("/tables")
    public Result<JsonNode> tables() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.listTables());
    }

    /** 某张表的结构（列定义 + 主键列）。 */
    @GetMapping("/table/columns")
    public Result<JsonNode> columns(@RequestParam String table) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.getColumns(table));
    }

    /** 某张表的完整索引清单（按索引名聚合，含唯一性 / 类型 / 有序列 / 备注）。只读。 */
    @GetMapping("/table/indexes")
    public Result<JsonNode> indexes(@RequestParam String table) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.getIndexes(table));
    }

    /** 分页读取一张表的数据（含列元信息与「是否可行内编辑」标记）。 */
    @GetMapping("/table/data")
    public Result<JsonNode> data(
            @RequestParam String table,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.getTableData(table, page, size));
    }

    /** 新增一行。body {@code {"table":"...","values":{列:值}}}。 */
    @PostMapping("/table/row")
    public Result<JsonNode> insertRow(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.insertRow(body.path("table").asText(""), body.path("values")));
    }

    /** 改一行。body {@code {"table":"...","pk":{主键列:值},"values":{列:值}}}。 */
    @PutMapping("/table/row")
    public Result<JsonNode> updateRow(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.updateRow(
                body.path("table").asText(""), body.path("pk"), body.path("values")));
    }

    /** 删一行。body {@code {"table":"...","pk":{主键列:值}}}。用 POST 而非 DELETE，避免带 body 的 DELETE 被代理剥掉。 */
    @PostMapping("/table/row/delete")
    public Result<JsonNode> deleteRow(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.deleteRow(body.path("table").asText(""), body.path("pk")));
    }

    /** 结构化改表结构。body {@code {"table":"...","op":{"action":"add|modify|drop|comment","name","dataType","nullable","defaultValue","comment","after"}}}。{@code action=comment} 改表级说明（无需 name）。 */
    @PostMapping("/table/alter")
    public Result<JsonNode> alterTable(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.alterTable(body.path("table").asText(""), body.path("op")));
    }

    /** 跑一条任意 SQL。body {@code {"sql":"..."}}。 */
    @PostMapping("/sql")
    public Result<JsonNode> runSql(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.runSql(body.path("sql").asText("")));
    }

    /** 备份列表（含保留天数与是否可用）。 */
    @GetMapping("/backups")
    public Result<JsonNode> backups() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.listBackups());
    }

    /** 立即备份一次（mysqldump 整库导出到 backups 目录）。 */
    @PostMapping("/backup")
    public Result<JsonNode> backup() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.backupNow());
    }

    /** 删除一个备份文件。body {@code {"name":"xxx.sql"}}。 */
    @PostMapping("/backup/delete")
    public Result<JsonNode> deleteBackup(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.deleteBackup(body.path("name").asText("")));
    }

    /** 读备份保留天数设置：{@code {retentionDays, available}}。 */
    @GetMapping("/settings")
    public Result<JsonNode> settings() {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.getSettings());
    }

    /** 设置备份保留天数（body {@code {"retentionDays": N}}，1..365），持久化并立即清理旧备份。 */
    @PutMapping("/settings")
    public Result<JsonNode> setSettings(@RequestBody JsonNode body) {
        CurrentUserHolder.requirePermission(PermissionCatalog.DATABASE_ACCESS);
        return Result.ok(databaseService.setSettings(body.path("retentionDays").asInt(-1)));
    }
}
