package com.familyhome.common.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限目录（RBAC 的"行"）。
 *
 * <p><b>为什么权限码是写死在代码里的常量，而"谁有哪些权限"却在库里</b>：角色固定三种
 * （{@code SUPER_ADMIN / ADMIN / MEMBER}，见 {@link com.familyhome.common.context.CurrentUserHolder}），
 * 权限项也是固定的一批（业务模块的 查看/管理 + 系统后台各页的 访问）——这两样都不让超管在页面上增删，
 * 否则就成了"自己给自己造一把新钥匙"。<b>可编辑的只有"某个角色勾选了哪些权限"这一层</b>
 * （存 {@code role_permission} 表，超管在「角色管理」页调），这就是需求要的"可编辑权限勾选矩阵"。
 *
 * <p><b>两类权限</b>：
 * <ul>
 *   <li>业务模块（菜谱/相册/文件/视频/点单/密码本）各有两档——{@code <mod>.view}（看得到这个菜单、能读）
 *       与 {@code <mod>.manage}（能新增/编辑/删除）。"普通成员只读、普通管理员能管"正是靠这两档区分：
 *       成员默认只有 {@code .view}，管理员两档都有。</li>
 *   <li>系统后台各页（账号管理/角色管理/目录管理/变更记录/部署管理/系统日志/操作记录/知识库/后端代码/前端代码/数据库）只有一个
 *       {@code <page>.access}——这些页要么是只读观测（日志/审计/变更记录），要么本身就是高危操作
 *       （部署/目录/账号/角色），没有"只读版"的意义，能进即能用。</li>
 * </ul>
 *
 * <p><b>后端强制的是 {@code .manage} 与 {@code .access}</b>（写操作与系统后台接口逐个判
 * {@code requirePermission}）；{@code .view} 主要驱动前端菜单可见性与路由 403，业务读接口默认三角色都放行
 * （见方案 v48 的口径说明）。这样"藏菜单只是体验、敲接口照样挡"这句老话在写操作上依然成立。
 *
 * <p>本类被三处共用：{@link com.familyhome.common.context.CurrentUserHolder}（超管短路取全量）、
 * user 域的 {@code RolePermissionService}（种子默认值 + 矩阵读写）、以及前端 shared/auth 的镜像常量。
 * 改这里等于改前端的矩阵行，两端必须同步。
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    // ===== 业务模块：查看 + 管理 =====
    public static final String RECIPE_VIEW = "recipe.view";
    public static final String RECIPE_MANAGE = "recipe.manage";
    public static final String ALBUM_VIEW = "album.view";
    public static final String ALBUM_MANAGE = "album.manage";
    public static final String FILE_VIEW = "file.view";
    public static final String FILE_MANAGE = "file.manage";
    public static final String VIDEO_VIEW = "video.view";
    public static final String VIDEO_MANAGE = "video.manage";
    public static final String ORDER_VIEW = "order.view";
    public static final String ORDER_MANAGE = "order.manage";
    public static final String VAULT_VIEW = "vault.view";
    public static final String VAULT_MANAGE = "vault.manage";

    // ===== 系统后台：单一 access =====
    public static final String USER_ACCESS = "user.access";
    public static final String ROLE_ACCESS = "role.access";
    public static final String STORAGE_ACCESS = "storage.access";
    public static final String CHANGELOG_ACCESS = "changelog.access";
    public static final String DEPLOY_ACCESS = "deploy.access";
    public static final String LOGS_ACCESS = "logs.access";
    public static final String OPLOG_ACCESS = "oplog.access";
    public static final String KNOWLEDGE_ACCESS = "knowledge.access";
    public static final String SERVER_REPO_ACCESS = "server-repo.access";
    public static final String WEB_REPO_ACCESS = "web-repo.access";
    public static final String DATABASE_ACCESS = "database.access";

    /** 一个权限项：码 + 中文标签（矩阵页/种子共用）。 */
    public record Perm(String code, String label) {
    }

    /**
     * 矩阵里的一"段"：业务模块段有两档（查看/管理），系统后台段每页一项（访问）。
     *
     * @param key     段标识（前端做行分组用）
     * @param label   段中文名
     * @param perms   这一段包含的权限项，顺序即矩阵里的列/行顺序
     */
    public record Group(String key, String label, List<Perm> perms) {
    }

    /** 全部权限段，顺序即「角色管理」页从上到下的展示顺序。不可变。 */
    public static final List<Group> GROUPS = List.of(
            new Group("recipe", "菜谱", List.of(
                    new Perm(RECIPE_VIEW, "查看菜谱/分类"),
                    new Perm(RECIPE_MANAGE, "管理菜谱/分类"))),
            new Group("order", "点单", List.of(
                    new Perm(ORDER_VIEW, "查看点单/统计/做法"),
                    new Perm(ORDER_MANAGE, "管理点单/做法"))),
            new Group("album", "相册", List.of(
                    new Perm(ALBUM_VIEW, "查看相册/图片"),
                    new Perm(ALBUM_MANAGE, "管理相册/图片"))),
            new Group("file", "文件", List.of(
                    new Perm(FILE_VIEW, "查看文件/分组"),
                    new Perm(FILE_MANAGE, "管理文件/分组"))),
            new Group("video", "视频", List.of(
                    new Perm(VIDEO_VIEW, "查看视频/分组"),
                    new Perm(VIDEO_MANAGE, "管理视频/分组"))),
            new Group("vault", "密码本", List.of(
                    new Perm(VAULT_VIEW, "查看/取回口令"),
                    new Perm(VAULT_MANAGE, "管理密码本条目"))),
            new Group("system", "系统后台", List.of(
                    new Perm(USER_ACCESS, "账号管理"),
                    new Perm(ROLE_ACCESS, "角色管理"),
                    new Perm(STORAGE_ACCESS, "目录管理"),
                    new Perm(CHANGELOG_ACCESS, "变更记录"),
                    new Perm(DEPLOY_ACCESS, "部署管理"),
                    new Perm(LOGS_ACCESS, "系统日志"),
                    new Perm(OPLOG_ACCESS, "操作记录"),
                    new Perm(KNOWLEDGE_ACCESS, "知识库"),
                    new Perm(SERVER_REPO_ACCESS, "后端代码"),
                    new Perm(WEB_REPO_ACCESS, "前端代码"),
                    new Perm(DATABASE_ACCESS, "数据库"))));

    /** 全部权限码（去重、按 GROUPS 顺序）。超管短路取的就是这一份。 */
    public static final List<String> ALL_CODES;

    /** 六个业务模块的 {@code .view} 码。成员默认拿这一份。 */
    public static final List<String> BUSINESS_VIEW = List.of(
            RECIPE_VIEW, ORDER_VIEW, ALBUM_VIEW, FILE_VIEW, VIDEO_VIEW, VAULT_VIEW);

    /** 六个业务模块的 {@code .manage} 码。普通管理员在 view 之外再多这一份。 */
    public static final List<String> BUSINESS_MANAGE = List.of(
            RECIPE_MANAGE, ORDER_MANAGE, ALBUM_MANAGE, FILE_MANAGE, VIDEO_MANAGE, VAULT_MANAGE);

    static {
        Set<String> codes = new LinkedHashSet<>();
        for (Group group : GROUPS) {
            for (Perm perm : group.perms()) {
                codes.add(perm.code());
            }
        }
        ALL_CODES = List.copyOf(new ArrayList<>(codes));
    }

    /** 这个码是不是目录里的合法权限项。保存矩阵时用它挡脏值。 */
    public static boolean isKnown(String code) {
        return code != null && ALL_CODES.contains(code);
    }
}
