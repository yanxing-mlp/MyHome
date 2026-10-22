package com.familyhome.user.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.auth.PermissionCatalog;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.user.biz.dao.RolePermissionMapper;
import com.familyhome.user.biz.entity.RolePermissionDO;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色↔权限矩阵：读某角色的权限、读整张矩阵、保存某角色的勾选（v48）。
 *
 * <p><b>三档角色，但只有两档进这张表</b>。超管（SUPER_ADMIN）拥有全部权限、由
 * {@code CurrentUserHolder.hasPermission} 短路，<b>不落库、不可编辑</b>——否则谁都能把超管的权限
 * 勾没了、把自己锁死在系统外。所以 {@link #permissionsOf} 对超管直接回全量，
 * {@link #save} 拒绝任何针对超管的写入，矩阵读接口里超管那一列由前端渲染成"全选且禁用"。
 *
 * <p><b>可编辑的只有"某角色勾了哪些权限码"</b>：角色（三种）与权限码（{@link PermissionCatalog} 一批）
 * 都是代码常量，页面上不给你增删，能动的就是这张关联表。改完立即生效——因为身份是逐请求从库里现查的
 * （{@code AppUserService#resolve} 会调 {@link #permissionsOf}），下一次请求就带着新的权限集合。
 *
 * <p><b>保存 = 按角色整批删了重插</b>，不是逐格 diff：一次勾选提交的就是"这个角色现在完整的权限清单"，
 * 删旧插新最不容易漏；靠 {@code uk_role_permission} 兜并发重复，整段包在一个事务里。
 *
 * <p><b>能改矩阵的只有超管</b>（{@code requireSuperAdmin} 硬门禁，见 save）——这与"能改角色的只有超管"
 * 是同一条护栏的两个面：如果普通管理员能改矩阵，他就能给自己勾上 {@code role.access} 甚至 {@code user.access}
 * 自我提权，矩阵就形同虚设。所以"改矩阵"和"改角色"都不进矩阵本身、都锁死在超管。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private final RolePermissionMapper rolePermissionMapper;

    /**
     * 某个角色当前拥有的权限码集合。超管短路为全量（{@link PermissionCatalog#ALL_CODES}），
     * 其余角色按 {@code role_permission} 表现值返回。给 {@code AppUserService#resolve} 逐请求调用。
     */
    public Set<String> permissionsOf(String role) {
        if (CurrentUserHolder.ROLE_SUPER_ADMIN.equals(role)) {
            return Set.copyOf(PermissionCatalog.ALL_CODES);
        }
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        List<RolePermissionDO> rows = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermissionDO>().eq(RolePermissionDO::getRole, role));
        Set<String> codes = new LinkedHashSet<>(rows.size());
        for (RolePermissionDO row : rows) {
            codes.add(row.getPermission());
        }
        return codes;
    }

    /**
     * 整张矩阵：权限段（行）+ 两个可编辑角色各自勾了哪些码。
     *
     * <p>权限段直接回 {@link PermissionCatalog#GROUPS}（代码常量，前端据此渲染行，两端不各存一份防漂移）；
     * 勾选只回 ADMIN / MEMBER 两档（超管恒全选，前端自己画禁用态，不从库里读）。
     * 读矩阵要有 {@code role.access} 权限（能进「角色管理」页）——但<b>保存</b>是超管专属，见 {@link #save}。
     */
    public MatrixVO matrix() {
        CurrentUserHolder.requirePermission(PermissionCatalog.ROLE_ACCESS);
        MatrixVO vo = new MatrixVO();
        vo.setGroups(PermissionCatalog.GROUPS);
        vo.setGrants(Map.of(
                CurrentUserHolder.ROLE_ADMIN, permissionsOf(CurrentUserHolder.ROLE_ADMIN),
                CurrentUserHolder.ROLE_MEMBER, permissionsOf(CurrentUserHolder.ROLE_MEMBER)));
        return vo;
    }

    /**
     * 保存某个可编辑角色的完整勾选（整批删旧插新）。
     *
     * <p>三道校验，顺序即报错优先级：
     * <ol>
     *   <li><b>只有超管能改矩阵</b>（{@code requireSuperAdmin}）——防普通管理员自我提权，见类注释。</li>
     *   <li><b>角色只能是 ADMIN / MEMBER</b>：超管不可编辑（短路全量，改了也没用且是自锁隐患），
     *       其余串是脏值。挡在这里而不是靠前端只给两列可编辑——绕开 UI 直接敲接口照样挡。</li>
     *   <li><b>每个权限码都必须在目录里</b>（{@link PermissionCatalog#isKnown}）：挡前端拼错或恶意塞的野码，
     *       否则库里会存进一条永远匹配不上的死权限。</li>
     * </ol>
     *
     * @param role  要改的角色，只认 ADMIN / MEMBER
     * @param codes 这个角色现在完整的权限码清单（去重后落库；空清单 = 把这个角色的权限全清空）
     */
    @Transactional
    public void save(String role, List<String> codes) {
        CurrentUserHolder.requireSuperAdmin();
        if (!CurrentUserHolder.ROLE_ADMIN.equals(role) && !CurrentUserHolder.ROLE_MEMBER.equals(role)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "只能编辑普通管理员或普通成员的权限");
        }
        Set<String> distinct = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                if (!PermissionCatalog.isKnown(code)) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "未知权限码：" + code);
                }
                distinct.add(code);
            }
        }

        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermissionDO>().eq(RolePermissionDO::getRole, role));
        for (String code : distinct) {
            RolePermissionDO row = new RolePermissionDO();
            row.setRole(role);
            row.setPermission(code);
            rolePermissionMapper.insert(row);
        }
        log.info("保存角色权限矩阵: role={}, 权限数={}", role, distinct.size());
    }

    /** 矩阵读接口的返回体：权限段（行）+ 各可编辑角色的勾选。 */
    public static class MatrixVO {
        private List<PermissionCatalog.Group> groups;
        private Map<String, Set<String>> grants;

        public List<PermissionCatalog.Group> getGroups() {
            return groups;
        }

        public void setGroups(List<PermissionCatalog.Group> groups) {
            this.groups = groups;
        }

        public Map<String, Set<String>> getGrants() {
            return grants;
        }

        public void setGrants(Map<String, Set<String>> grants) {
            this.grants = grants;
        }
    }
}
