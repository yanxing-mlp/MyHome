package com.familyhome.user.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.user.biz.service.RolePermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理 B 端接口（v48）：读整张权限矩阵 + 保存某个角色的勾选。
 *
 * <p><b>只有两条</b>，对应「角色管理」页的两件事——进页面拉一次矩阵（{@code GET /matrix}），
 * 勾完保存（{@code PUT /matrix}）。角色固定三种、权限码固定一批（都在
 * {@link com.familyhome.common.auth.PermissionCatalog}），页面上不给增删，所以没有第三条。
 *
 * <p><b>两条的门槛不一样</b>，都在 {@link RolePermissionService} 里判、不写在拦截器：
 * <ul>
 *   <li>读矩阵要 {@code role.access}（能进这一页就能看现状）；</li>
 *   <li><b>保存是超管专属</b>（{@code requireSuperAdmin}）——否则普通管理员能给自己勾上
 *       {@code role.access}/{@code user.access} 自我提权，矩阵就形同虚设。这与"改角色只有超管"是同一条护栏。</li>
 * </ul>
 *
 * <p>超管那一列不进库、不可编辑：{@code GET /matrix} 只回 ADMIN / MEMBER 两档的勾选，
 * 超管恒拥有全部权限（{@code CurrentUserHolder.hasPermission} 短路），前端把这一列渲染成"全选且禁用"。
 */
@Validated
@RestController
@RequestMapping("/api/b/role")
@RequiredArgsConstructor
public class RoleController {

    private final RolePermissionService rolePermissionService;

    /** 整张矩阵：权限段（行，代码常量）+ ADMIN / MEMBER 两档各自勾了哪些码。要 {@code role.access}。 */
    @GetMapping("/matrix")
    public Result<RolePermissionService.MatrixVO> matrix() {
        return Result.ok(rolePermissionService.matrix());
    }

    /**
     * 保存某个角色的完整勾选（整批删旧插新）。<b>仅超管</b>；角色只认 ADMIN / MEMBER，
     * 权限码逐个校验在目录内（详见 {@link RolePermissionService#save}）。
     */
    @PutMapping("/matrix")
    public Result<Void> saveMatrix(@Valid @RequestBody SaveMatrixRequest request) {
        rolePermissionService.save(request.getRole(), request.getCodes());
        return Result.ok();
    }

    /** 保存矩阵的入参：一个角色 + 它现在完整的权限码清单（空清单 = 清空该角色权限）。 */
    @Data
    public static class SaveMatrixRequest {

        @NotBlank(message = "请指定角色")
        private String role;

        private List<String> codes;
    }
}
