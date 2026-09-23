package com.familyhome.boot.controller.b;

import com.familyhome.album.biz.service.AlbumPartition;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端表单只读预检聚合：只返回是否重复，不返回命中记录，避免分页/搜索结果漏检。
 * POST 防止手机号、密码本账号进入 URL；没有密码参数，也不记录请求体。
 * 写接口仍独立校验并由唯一索引兜底，预检结果不是写入凭证。
 */
@RestController
@RequestMapping("/api/b/validation")
@RequiredArgsConstructor
public class DuplicateValidationController {
    private final JdbcTemplate jdbc;

    public enum Kind {
        RECIPE, RECIPE_CATEGORY, PRACTICE_GROUP, PRACTICE_OPTION,
        ALBUM_GROUP, USER_NAME, USER_PHONE, VAULT_ACCOUNT, FILE_CATEGORY
    }

    public record DuplicateRequest(
            @NotNull Kind kind,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 128) String account,
            @Pattern(regexp = "FAMILY|PERSONAL|PUBLIC|PRIVATE", message = "数据分区不正确") String scope,
            @Positive Long excludeId,
            @Positive Long groupId) {
    }

    @PostMapping("/duplicate")
    @Transactional(readOnly = true)
    public Result<Boolean> duplicate(@Valid @RequestBody DuplicateRequest request) {
        Long currentId = CurrentUserHolder.requireUserId();
        // 对齐账号写入口：新建只允许管理员，个人资料只能排除自己。
        if (request.kind() == Kind.USER_NAME || request.kind() == Kind.USER_PHONE) {
            if (request.excludeId() == null) {
                CurrentUserHolder.requireAdmin();
            } else if (!currentId.equals(request.excludeId())) {
                throw BizException.of(ErrorCode.USER_FORBIDDEN, "只能校验自己的个人资料");
            }
        }

        String name = request.name().trim();
        if (name.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "名称不能为空");
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(name);
        // SQL 片段全部来自固定枚举，用户输入一律绑定参数；生成列与写入唯一索引同口径。
        String query = switch (request.kind()) {
            case RECIPE -> "SELECT 1 FROM recipe WHERE unique_name = ?";
            case RECIPE_CATEGORY -> "SELECT 1 FROM recipe_category WHERE unique_name = ?";
            case PRACTICE_GROUP -> "SELECT 1 FROM recipe_practice_group WHERE unique_name = ?";
            case FILE_CATEGORY -> {
                DataPartition partition = DataPartition.forRequest(parseScope(request.scope(), DataScope.class));
                parameters.add(partition.scope().name());
                parameters.add(partition.ownerId());
                yield "SELECT 1 FROM file_category WHERE unique_name = ? AND scope = ? AND owner_id = ?";
            }
            case USER_NAME -> "SELECT 1 FROM app_user WHERE unique_name = ?";
            case USER_PHONE -> "SELECT 1 FROM app_user WHERE unique_phone = ?";
            case PRACTICE_OPTION -> {
                if (request.groupId() == null) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "请选择做法分组");
                }
                parameters.add(request.groupId());
                yield "SELECT 1 FROM recipe_practice_option WHERE unique_name = ? AND group_id = ?";
            }
            case ALBUM_GROUP -> {
                AlbumPartition partition = AlbumPartition.forRequest(parseScope(request.scope(), AlbumScope.class));
                parameters.add(partition.scope().name());
                parameters.add(partition.ownerId());
                yield "SELECT 1 FROM album_group WHERE unique_name = ? AND scope = ? AND unique_owner = ?";
            }
            case VAULT_ACCOUNT -> {
                if (request.account() == null || request.account().trim().isEmpty()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "请输入账号");
                }
                parameters.add(request.account().trim());
                DataPartition partition = DataPartition.forRequest(parseScope(request.scope(), DataScope.class));
                parameters.add(partition.scope().name());
                parameters.add(partition.ownerId());
                yield "SELECT 1 FROM vault_account WHERE unique_name = ? AND unique_account = ? AND scope = ? AND owner_id = ?";
            }
        };
        if (request.excludeId() != null) {
            query += " AND id <> ?";
            parameters.add(request.excludeId());
        }
        query += " LIMIT 1";
        return Result.ok(!jdbc.queryForList(query, parameters.toArray()).isEmpty());
    }

    /** 同一只读入口服务两套域枚举，不能把 PUBLIC 当作相册 FAMILY 静默转换。 */
    private static <T extends Enum<T>> T parseScope(String scope, Class<T> type) {
        if (scope == null) return null;
        try {
            return Enum.valueOf(type, scope);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "数据分区不正确");
        }
    }
}
