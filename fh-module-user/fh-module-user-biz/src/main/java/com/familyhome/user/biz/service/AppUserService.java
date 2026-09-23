package com.familyhome.user.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.auth.SessionToken;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.crypto.TransportCipher;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.dto.FileDTO;
import com.familyhome.user.biz.dao.AppUserMapper;
import com.familyhome.user.biz.entity.AppUserDO;
import com.familyhome.user.biz.manager.UserPasswordManager;
import com.familyhome.user.biz.model.request.UserCreateRequest;
import com.familyhome.user.biz.model.request.UserLoginRequest;
import com.familyhome.user.biz.model.request.UserPasswordUpdateRequest;
import com.familyhome.user.biz.model.request.UserProfileUpdateRequest;
import com.familyhome.user.biz.model.request.UserRoleUpdateRequest;
import com.familyhome.user.biz.model.vo.admin.UserAdminVO;
import com.familyhome.user.biz.model.vo.admin.UserBriefVO;
import com.familyhome.user.biz.model.vo.admin.UserOptionVO;
import com.familyhome.user.biz.model.vo.admin.UserProfileVO;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号业务：登录、个人中心（自己看自己/改自己），以及只有管理员能做的建号与删号。
 *
 * <p><b>本域最重要的一条边界是"管理员管的是身份，不是凭据"</b>：账号管理页能列人、能加人、能删人、
 * 能定谁是超管，但<b>改不了任何人的资料与口令</b>——昵称/手机号/口令/头像四项都只有本人能在个人中心动
 * （头像建号时由管理员先定一张，之后本人可在个人中心自己换）。口令这一项更彻底：
 * 建号时那个初始口令是服务端给的一个全家统一的固定值（{@link #DEFAULT_INITIAL_PASSWORD}），
 * 管理员既没输入过、也看不到，之后更没有重置的口子。所以"有人忘了口令"在这个系统里没有自助恢复路径，
 * 唯一出路是删号重建（历史行的"添加人"会变成"已删除账号"）。这是用户 2026-09-21 明确要的口径，
 * <b>不要为了"方便"把编辑/重置那两条加回来</b>；要开也只开 {@link #updateRole} 这种"一次只写一列"的形状。
 *
 * <p><b>另外四条读起来不像注释的约束</b>：
 * <ol>
 *   <li><b>口令与手机号都不进日志</b>。明文口令、哈希、以及"新密码是什么"都不打；日志是比数据库
 *       更容易被看到的地方，所以 {@link #login} 成功/失败、{@link #updatePassword} 都只记
 *       {@code id} 和 {@code name}。</li>
 *   <li><b>哈希不出 DAO</b>：{@code AppUserDO.passwordHash} 标了 {@code select = false}，
 *       本类里任何 {@code selectById} / 列表查询都拿不到它，只有 {@link #login} 与
 *       {@link #updatePassword} 各调一次 {@code selectPasswordHashById}，产物直接进
 *       {@code matches()}，不停留在字段或返回值里。</li>
 *   <li><b>{@code role} 全库只有一个写入口</b>：{@link #updateRole}（{@code PUT /api/b/user/{id}/role}，
 *       只写这一列）。{@code UserCreateRequest} 与 {@code UserProfileUpdateRequest} 都刻意不收 {@code role}，
 *       新建恒为 MEMBER，而那条"一次把整行改掉"的 {@code PUT /{id}} 不存在，所以想提权只有那一格一条路。
 *       默认超管是 {@code V500} 种子里的大宝；{@link #updateRole} 又挡住了"改自己"和"降掉最后一个管理员"，
 *       于是既不存在"悄悄把自己提成管理员"，也不存在"全家没人能管账号"。</li>
 *   <li><b>删除是软删，且被删的人留下的 {@code creator_id} 不改写</b>：
 *       "这条记录当初是谁加的"是历史事实，人不在了也要留痕；前端的添加人字典
 *       对查不到的 id 显示"已删除账号"，而不是把记录改成大宝。</li>
 * </ol>
 *
 * <p>昵称、手机号按去除首尾空格后的值预检，只检查未删除账号；数据库唯一索引兜底并发冲突，
 * 由全局异常处理转换为 409。软删除释放占用，允许删号后使用同名、同手机号重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserService {

    /**
     * 新建账号的统一初始口令，与 V500/V502 两行种子里那个 123456 是同一个值。
     *
     * <p>它不是"一个可以公开的弱口令"这种妥协，而是这一版设计的直接结果：管理员不接触任何人的口令，
     * 所以初始口令必须有一个不来自任何人的默认值。当事人第一次登录后去个人中心换掉（新口令 6–64 位）。
     * 明文写成常量而不是配置项——它只是一个"第一次进门用的临时口令"，改它不影响任何存量哈希。
     */
    public static final String DEFAULT_INITIAL_PASSWORD = "123456";

    private final AppUserMapper appUserMapper;
    private final FileFacade fileFacade;
    private final UserPasswordManager passwordManager;
    /** 传输段解密：前端送上来的是密文，进业务逻辑之前才变回明文。见 {@link TransportCipher}。 */
    private final TransportCipher transportCipher;
    /** 登录成功后签发会话令牌，取代可被前端伪造的 {@code X-User-Id}。见 {@link SessionToken}。 */
    private final SessionToken sessionToken;

    /**
     * 登录页下拉框 + 各页"添加人"的 id → 昵称字典。白名单接口，不需要登录令牌。
     *
     * <p>按 id 正序（大宝 1、小宝 2），不是按拼音——一期两个人，将来多了也不值得为排序建列。
     */
    public List<UserOptionVO> options() {
        List<AppUserDO> users = appUserMapper.selectList(
                new LambdaQueryWrapper<AppUserDO>().orderByAsc(AppUserDO::getId));
        return users.stream().map(user -> {
            UserOptionVO vo = new UserOptionVO();
            vo.setId(user.getId());
            vo.setName(user.getName());
            return vo;
        }).toList();
    }

    /**
     * 登录：核对"下拉框选的人"和"手填的口令"是否对得上。
     *
     * <p>两次查询是刻意的：第一次 {@code selectById} 拿的是那一行（昵称用于日志与返回体，
     * 而它<b>取不到哈希</b>——那一列被标了 {@code select = false}），第二次才单独把哈希捞出来。
     * 顺序反过来的话，"账号不存在"和"密码不对"就得靠一次 null 判断分岔，
     * 而分完岔还要再查一次才能组出返回体。
     *
     * <p>两种失败的说法不一样：账号没了说"账号不存在，请重新选择"，口令错了只说"密码与该账号不匹配"。
     * 这里不需要防枚举（家里几个人本来就在下拉框里全列着），只需要让用户知道下一步该改哪个输入框。
     *
     * <p><b>口令对上了才签发令牌</b>：返回体里那一格 {@code token} 是服务端用 HMAC 签过名的，
     * 前端存本机、之后每个请求放进 {@code Authorization: Bearer} 头。这正是替换掉"前端自报
     * {@code X-User-Id}"的那道修复——身份不再由客户端说了算，改由服务端验签验有效期（见 {@link SessionToken}）。
     * 令牌里只有 userId 和过期时间，<b>不含角色</b>：角色每次请求都从库里现查（{@link #resolve}），
     * 于是改了角色、删了号立刻生效，不必等旧令牌过期。
     */
    public UserBriefVO login(UserLoginRequest request) {
        // 先解密再判业务：解不开是"这个请求体不合法"（400），与"账号在不在/口令对不对"是两类事，
        // 顺序反了会让一个前端加密没配好的故障看起来像"你的账号有问题"。
        String password = transportCipher.decrypt(request.getPassword());
        AppUserDO user = appUserMapper.selectById(request.getUserId());
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "账号不存在，请重新选择");
        }
        String passwordHash = appUserMapper.selectPasswordHashById(user.getId());
        if (!passwordManager.matches(password, passwordHash)) {
            log.warn("登录口令不匹配: id={}, name={}", user.getId(), user.getName());
            throw BizException.of(ErrorCode.USER_PASSWORD_MISMATCH, "密码与该账号不匹配");
        }
        log.info("账号登录: id={}, name={}", user.getId(), user.getName());
        UserBriefVO brief = toBrief(user, avatarUrls(List.of(user)));
        // 令牌只在这一处签发、只随 /login 返回；绝不落日志（它是一条活的凭据）。
        brief.setToken(sessionToken.issue(user.getId()));
        return brief;
    }

    /**
     * 当前登录者。前端每次启动都用它把本机缓存的 userId 换成"还在不在 + 现在的头像和角色"，
     * 所以被删号的、被改头像的人不需要手动清缓存。
     */
    public UserBriefVO me() {
        AppUserDO user = requireExists(CurrentUserHolder.requireUserId());
        return toBrief(user, avatarUrls(List.of(user)));
    }

    /**
     * 个人中心要显示的那份"我自己"：昵称 + 手机号 + 头像。
     *
     * <p>它比 {@link #me()} 多的正是手机号——那一格要写进本机长期缓存，所以不带；
     * 这一条只在人主动打开个人中心时打一次，谁都不缓存它。
     *
     * <p>头像这里给两样：{@code avatarFileId}（让页面知道"当前这张"是哪个文件，保存时原样带回、
     * 没换头像就不会误清）与 {@code avatarUrl}（页面进来先预览当前头像，缩略图，取不到就画默认剪影）。
     */
    public UserProfileVO profile() {
        AppUserDO user = requireExists(CurrentUserHolder.requireUserId());
        Map<Long, String> avatars = avatarUrls(List.of(user));
        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setPhone(user.getPhone());
        vo.setAvatarFileId(user.getAvatarFileId());
        vo.setAvatarUrl(user.getAvatarFileId() == null ? null : avatars.get(user.getAvatarFileId()));
        return vo;
    }

    /**
     * 个人中心改自己的昵称/手机号/头像。<b>不判 ADMIN</b>：判据是 {@code requireUserId()}，
     * 而改的目标就是当前登录的那个人，所以既没有越权对象，也不需要"我是管理员"这层授权。
     *
     * <p>这一条同时也是<b>全系统唯一能改昵称/手机号的地方</b>——账号管理页没有编辑入口，
     * 管理员改不了别人的资料，写错了也只能删号重建。入参里没有 {@code role}、也没有
     * {@code password}（改口令是 {@link #updatePassword}），所以这条自助接口能写的列只有三列，
     * 不存在"顺手改角色"的路径。
     *
     * <p><b>头像这一格为 null 时不进 UPDATE</b>（MyBatis-Plus {@code updateById} 默认 {@code NOT_NULL} 策略）：
     * 于是"没换头像"等于"保持原样"，这条能换头像但清不掉头像。前端提交的恒是当前 state 里那个 id
     * （没动过就是 {@link #profile()} 带回来的原 id），不会传 null——真正传 null 只可能是绕过前端的裸调用。
     */
    @Transactional
    public void updateProfile(UserProfileUpdateRequest request) {
        Long id = CurrentUserHolder.requireUserId();
        requireExists(id);
        String name = request.getName().trim();
        String phone = request.getPhone().trim();
        requireNameUnused(name, id);
        requirePhoneUnused(phone, id);

        AppUserDO patch = new AppUserDO();
        patch.setId(id);
        patch.setName(name);
        patch.setPhone(phone);
        // null 时 updateById 会跳过这一列（NOT_NULL 策略），所以"没换头像"不会把原来的清掉
        patch.setAvatarFileId(request.getAvatarFileId());
        int rows = appUserMapper.updateById(patch);
        if (rows == 0) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "该账号已被删除，请重新登录");
        }
        log.info("修改个人资料: id={}, name={}", id, name);
    }

    /**
     * 个人中心改自己的口令：先核对原口令，再写新哈希。<b>这是全系统唯一一条改口令的路</b>。
     *
     * <p>原密码这一道是给"设备没锁屏被家人顺手拿起来"准备的（这条接口只要求登录态，
     * 不要求管理员）。管理员那边没有重置口令的口子——账号管理页连别人的账号都改不了，
     * 所以忘了口令只能删号重建，<b>别为了"顺手"再开一条 {@code requireAdmin()} 的重置接口</b>：
     * 那等于把这一轮刻意收走的"管理员可以替任何人换凭据"这条路原样放回。
     *
     * <p>新口令与旧的一样、或者很弱，本方法<b>都不管</b>：一期不做口令强度规则（理由见
     * {@code UserPasswordUpdateRequest}）。
     */
    @Transactional
    public void updatePassword(UserPasswordUpdateRequest request) {
        Long id = CurrentUserHolder.requireUserId();
        AppUserDO user = requireExists(id);
        // 长度校验只能放在解密之后：DTO 里那一格装的是一串比明文还长的 base64 密文，
        // @Size(min=6,max=64) 加在它上面会把每一个合法请求都判成"太短/太长"。
        String oldPassword = transportCipher.decrypt(request.getOldPassword());
        String newPassword = transportCipher.decrypt(request.getNewPassword());
        requireNewPasswordLength(newPassword);
        String passwordHash = appUserMapper.selectPasswordHashById(id);
        if (!passwordManager.matches(oldPassword, passwordHash)) {
            log.warn("修改口令时原密码不匹配: id={}, name={}", id, user.getName());
            throw BizException.of(ErrorCode.USER_PASSWORD_MISMATCH, "原密码不正确");
        }

        AppUserDO patch = new AppUserDO();
        patch.setId(id);
        patch.setPasswordHash(passwordManager.hash(newPassword));
        int rows = appUserMapper.updateById(patch);
        if (rows == 0) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "该账号已被删除，请重新登录");
        }
        log.info("修改口令: id={}, name={}", id, user.getName());
    }

    /**
     * 新口令 6–64 位。校验点从 {@code UserPasswordUpdateRequest} 的 {@code @Size} 搬到这里，
     * 因为那一格现在是密文——**判长度必须判在明文上**，否则限制的就不是"用户选的口令有多长"，
     * 而是"前端那套加密把字符串撑到了多长"，那是完全无关的一件事。
     */
    private void requireNewPasswordLength(String newPassword) {
        if (newPassword.length() < 6 || newPassword.length() > 64) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新密码至少 6 位、最多 64 位");
        }
    }

    /** 账号管理列表。整个方法只服务 ADMIN，读接口也一样挡，因为里面有手机号。 */
    public List<UserAdminVO> list() {
        CurrentUserHolder.requireAdmin();
        List<AppUserDO> users = appUserMapper.selectList(
                new LambdaQueryWrapper<AppUserDO>().orderByAsc(AppUserDO::getId));
        Map<Long, String> avatars = avatarUrls(users);
        return users.stream().map(user -> toAdminVO(user, avatars)).toList();
    }

    /**
     * 管理员加一个人。口令是服务端那个统一初始值，不来自请求体（理由见
     * {@link #DEFAULT_INITIAL_PASSWORD}），所以本方法连明文都收不到，更谈不上记录它。
     */
    @Transactional
    public Long create(UserCreateRequest request) {
        CurrentUserHolder.CurrentUser admin = CurrentUserHolder.requireAdmin();
        String name = request.getName().trim();
        String phone = request.getPhone().trim();
        requireNameUnused(name, null);
        requirePhoneUnused(phone, null);

        AppUserDO entity = new AppUserDO();
        entity.setName(name);
        entity.setPhone(phone);
        // 只存哈希：连那个统一的初始口令都不以明文形式落到任何字段、返回值或日志里
        entity.setPasswordHash(passwordManager.hash(DEFAULT_INITIAL_PASSWORD));
        entity.setAvatarFileId(request.getAvatarFileId());
        entity.setRole(CurrentUserHolder.ROLE_MEMBER);
        appUserMapper.insert(entity);

        log.info("新增账号: id={}, name={}, role={}, 操作人={}",
                entity.getId(), name, entity.getRole(), admin.name());
        return entity.getId();
    }

    /**
     * 管理员设置某个人的角色。<b>这是管理员唯一能替别人写的一格</b>：整条只更新 {@code role} 一列，
     * 昵称/手机号/口令/头像都不在这条的入参里（那四项只有本人在个人中心动）。
     *
     * <p><b>它换不到任何凭据</b>：改完这个角色，操作人既看不到、也设不了对方的口令——
     * 那条"管理员替别人重置口令"的接口在这一版仍然不存在，忘了口令的兜底还是删号重建。
     * 这一条买到的只是"谁能进账号管理这一页"。
     *
     * <p>两道护栏与 {@link #delete} 同构、同顺序，为的是单人管理员时提示语说得通：
     * 唯一的管理员来降自己，先报"至少保留一个管理员"，因为那句"请让另一位管理员操作"在这种情况下没人可请。
     * 之所以连"降自己"都要挡：本机那一格存着 {@code role}，降完界面还挂着"我是管理员"，
     * 菜单在、请求全 403，得等下一次 {@code /me} 校准才对得上——与删自己是同一类坑。
     *
     * <p>角色值只认 {@code ADMIN} / {@code MEMBER} 两个串（与 {@code CurrentUserHolder} 的常量对齐），
     * 其余一律 400。这一列没有 CHECK 约束，脏值会让那个人既进不了账号管理也不是普通成员。
     */
    @Transactional
    public void updateRole(Long id, UserRoleUpdateRequest request) {
        CurrentUserHolder.CurrentUser admin = CurrentUserHolder.requireAdmin();
        String role = request.getRole();
        if (!CurrentUserHolder.ROLE_ADMIN.equals(role) && !CurrentUserHolder.ROLE_MEMBER.equals(role)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "角色只能是 ADMIN 或 MEMBER");
        }
        AppUserDO target = requireExists(id);

        if (CurrentUserHolder.ROLE_ADMIN.equals(target.getRole())
                && CurrentUserHolder.ROLE_MEMBER.equals(role)
                && countByRole(CurrentUserHolder.ROLE_ADMIN) <= 1) {
            throw BizException.of(ErrorCode.USER_FORBIDDEN, "至少保留一个管理员，否则没人能再管理账号");
        }
        if (Objects.equals(admin.id(), id)) {
            throw BizException.of(ErrorCode.USER_FORBIDDEN, "不能修改自己的角色，请让另一位管理员操作");
        }

        AppUserDO patch = new AppUserDO();
        patch.setId(id);
        patch.setRole(role);
        int rows = appUserMapper.updateById(patch);
        if (rows == 0) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "该账号已被删除");
        }
        log.info("设置角色: id={}, name={}, role={}, 操作人={}", id, target.getName(), role, admin.name());
    }

    /**
     * 删除账号（软删）。管理员对"别人能不能登进来"的另一格控制权（与 {@link #updateRole} 一起构成全部）：
     * 能加人、能删人、能定谁是超管，但改不了任何人的资料与口令。
     *
     * <p><b>管理员账号一律不可删</b>：要删掉某个管理员，得先由另一位管理员把他的角色降成普通成员
     * （{@link #updateRole}），降下来之后那一行才会出现删除按钮。这一条同时天然挡住了"删自己"——
     * 能走到这里的操作人必然是管理员，他那一行正是 ADMIN，所以永远删不到自己，也就不再需要单独的
     * 自我删除分支；"至少保留一个管理员"的计数同样多余：管理员根本删不掉，最后一个管理员自然还在。
     */
    @Transactional
    public void delete(Long id) {
        CurrentUserHolder.CurrentUser admin = CurrentUserHolder.requireAdmin();
        AppUserDO target = requireExists(id);

        if (CurrentUserHolder.ROLE_ADMIN.equals(target.getRole())) {
            throw BizException.of(ErrorCode.USER_FORBIDDEN, "管理员账号不能删除，请先取消其管理员角色");
        }

        appUserMapper.deleteById(id);
        log.info("删除账号: id={}, name={}, 操作人={}", id, target.getName(), admin.name());
    }

    /**
     * 把请求头里的 userId 换成线程上下文，只给 fh-boot 的拦截器调用。
     *
     * <p>返回 {@code CurrentUser} 而不是 {@code AppUserDO}：fh-boot 因此不需要认识本域的实体类，
     * 也就不会有人顺手把整行（含手机号）塞进响应。
     */
    public CurrentUserHolder.CurrentUser resolve(Long id) {
        AppUserDO user = appUserMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "账号不存在或已被删除，请重新登录");
        }
        return new CurrentUserHolder.CurrentUser(user.getId(), user.getName(), user.getRole());
    }

    private AppUserDO requireExists(Long id) {
        AppUserDO user = id == null ? null : appUserMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND, "账号不存在或已被删除");
        }
        return user;
    }

    private void requireNameUnused(String name, Long excludeId) {
        if (name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入昵称");
        }
        LambdaQueryWrapper<AppUserDO> wrapper = new LambdaQueryWrapper<AppUserDO>()
                .apply("TRIM(name) = {0}", name);
        if (excludeId != null) {
            wrapper.ne(AppUserDO::getId, excludeId);
        }
        if (appUserMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.USER_NAME_DUPLICATED, "已有同名账号：" + name);
        }
    }

    private void requirePhoneUnused(String phone, Long excludeId) {
        if (phone.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入手机号");
        }
        LambdaQueryWrapper<AppUserDO> wrapper = new LambdaQueryWrapper<AppUserDO>()
                .apply("TRIM(phone) = {0}", phone);
        if (excludeId != null) {
            wrapper.ne(AppUserDO::getId, excludeId);
        }
        if (appUserMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.USER_PHONE_DUPLICATED, "该手机号已被其他账号使用");
        }
    }

    private long countByRole(String role) {
        return appUserMapper.selectCount(
                new LambdaQueryWrapper<AppUserDO>().eq(AppUserDO::getRole, role));
    }

    /**
     * 一次批量拿头像 URL，禁止在循环里调 {@code fileFacade}（列表页 N 个人就是 N 次跨域调用）。
     *
     * <p>优先用缩略图：头像都是几十像素的圆，原图动辄几 MB，列表页全部加载会很难看。
     * 缩略图生成失败（{@code thumbUrl} 为 null）或文件已被删（id 不在 map 里）时回退，
     * 最终拿不到就是 null，前端画默认头像。
     */
    private Map<Long, String> avatarUrls(Collection<AppUserDO> users) {
        List<Long> fileIds = users.stream()
                .map(AppUserDO::getAvatarFileId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FileDTO> files = fileFacade.mapByIds(fileIds);
        Map<Long, String> urls = new HashMap<>(files.size());
        files.forEach((fileId, file) ->
                urls.put(fileId, file.getThumbUrl() != null ? file.getThumbUrl() : file.getUrl()));
        return urls;
    }

    private UserBriefVO toBrief(AppUserDO user, Map<Long, String> avatars) {
        UserBriefVO vo = new UserBriefVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setRole(user.getRole());
        vo.setAvatarUrl(user.getAvatarFileId() == null ? null : avatars.get(user.getAvatarFileId()));
        return vo;
    }

    private UserAdminVO toAdminVO(AppUserDO user, Map<Long, String> avatars) {
        UserAdminVO vo = new UserAdminVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole());
        vo.setAvatarUrl(user.getAvatarFileId() == null ? null : avatars.get(user.getAvatarFileId()));
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }
}
