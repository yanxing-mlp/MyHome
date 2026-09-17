package com.familyhome.boot.controller.b;

import com.familyhome.common.result.Result;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查。M1 的验收入口——同时验证 Web 层、统一响应结构和数据库连通性。
 *
 * <p>放在 {@code /api/b/} 下而不是 actuator，是为了顺带验证 nginx 的内网白名单规则
 * 会不会把它一起挡住（会，这是预期的：health 属于 B 端路径）。
 */
@RestController
@RequestMapping("/api/b")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;

    @Value("${fh.storage.root}")
    private String storageRoot;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "family-home-server");
        data.put("time", OffsetDateTime.now().toString());
        data.put("java", System.getProperty("java.version"));
        data.put("storageRoot", storageRoot);
        data.put("db", probeDatabase());
        return Result.ok(data);
    }

    private String probeDatabase() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            return "UNAVAILABLE: " + e.getMessage();
        }
    }
}
