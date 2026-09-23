package com.familyhome.vault.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.common.context.CurrentUserHolder;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.crypto.TransportCipher;
import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.common.result.PageResult;
import com.familyhome.vault.biz.converter.VaultAccountConverter;
import com.familyhome.vault.biz.dao.VaultAccountMapper;
import com.familyhome.vault.biz.entity.VaultAccountDO;
import com.familyhome.vault.biz.manager.VaultCipherManager;
import com.familyhome.vault.biz.model.request.VaultAccountCreateRequest;
import com.familyhome.vault.biz.model.request.VaultAccountUpdateRequest;
import com.familyhome.vault.biz.model.vo.admin.VaultAccountAdminVO;
import com.familyhome.vault.biz.model.vo.admin.VaultPasswordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 密码本业务。
 *
 * <p>所有操作先通过 {@link DataPartition#forRequest} 校验登录并确定 scope + ownerId。
 * 公共分区 ownerId=0，私人分区 ownerId=当前账号；ADMIN 不例外，跨分区 ID 与不存在统一 404。
 * {@code @TableLogic} 自动给 BaseMapper 操作附加 {@code deleted = 0}，显式口令 SQL 同时限定分区和软删状态。
 *
 * <p>存储层口令只在 {@link #reveal} 路径解密；{@code update} 保留 "password 为空即不改"
 * 的语义，靠 MyBatis-Plus 默认的 NOT_NULL 更新策略实现：{@code patch.passwordEnc == null}
 * 时该列不会进入 SET 子句。创建后的 scope、ownerId、creatorId 不可修改。
 *
 * <p>HTTP 报文使用 {@link TransportCipher 传输层密文}，数据库使用
 * {@link VaultCipherManager 存储层密文}，两者密钥独立。创建/改密走传输层解密后存储层加密，
 * reveal 反向转换；明文仅用于内存中的转换与校验，<b>不落库、不返回、不进日志</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultAccountService {

    /** 明文口令的长度上限（v11 起这条规则从 DTO 的 bean 校验挪到了这里——解密之后才看得到真实长度）。 */
    private static final int MAX_PASSWORD_CHARS = 256;

    private final VaultAccountMapper vaultAccountMapper;
    private final VaultCipherManager vaultCipherManager;
    private final TransportCipher transportCipher;

    /**
     * 分页列表。<b>默认按更新时间倒序</b>（最近动过的排前面），不做"名称拼音排序"——
     * 那需要 GBK 字符集或拼音列，家庭场景下"最近改动"更有用。
     */
    public PageResult<VaultAccountAdminVO> page(DataScope scope, String keyword, long pageNo, int pageSize) {
        DataPartition partition = DataPartition.forRequest(scope);
        LambdaQueryWrapper<VaultAccountDO> query = partitionQuery(partition);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(VaultAccountDO::getName, kw)
                    .or()
                    .like(VaultAccountDO::getAccount, kw));
        }
        query.orderByDesc(VaultAccountDO::getUpdateTime).orderByDesc(VaultAccountDO::getId);

        Page<VaultAccountDO> result = vaultAccountMapper.selectPage(new Page<>(pageNo, pageSize), query);
        return PageResult.of(
                result.getRecords().stream().map(VaultAccountConverter::toAdminVO).toList(),
                result.getTotal(),
                pageNo,
                pageSize);
    }

    @Transactional
    public Long create(DataScope scope, VaultAccountCreateRequest request) {
        DataPartition partition = DataPartition.forRequest(scope);
        String password = decryptAndCheckLength(request.getPassword());
        String name = request.getName().trim();
        String account = request.getAccount().trim();
        requireAccountUnused(partition, name, account, null);
        VaultAccountDO entity = new VaultAccountDO();
        entity.setName(name);
        entity.setAccount(account);
        entity.setPasswordEnc(vaultCipherManager.encrypt(password));
        entity.setScope(partition.scope());
        entity.setOwnerId(partition.ownerId());
        entity.setCreatorId(CurrentUserHolder.requireUserId());
        vaultAccountMapper.insert(entity);
        // 只记 id 和 name，绝不记请求对象本身（toString 含口令），也绝不记那个解出来的明文
        log.info("新增密码本条目: id={}, name={}", entity.getId(), entity.getName());
        return entity.getId();
    }

    @Transactional
    public void update(DataScope scope, Long id, VaultAccountUpdateRequest request) {
        DataPartition partition = DataPartition.forRequest(scope);
        requireExists(id, partition);

        // 注意判空的是"密文在不在"，不是"明文在不在"：前端不打算改口令时根本不会带这个字段。
        // 解密的活儿必须在 hasText 之后，否则"只改名字"那条正常请求会撞上一句"请输入密码"。
        String password = StringUtils.hasText(request.getPassword())
                ? decryptAndCheckLength(request.getPassword())
                : null;
        String name = request.getName().trim();
        String account = request.getAccount().trim();
        requireAccountUnused(partition, name, account, id);

        VaultAccountDO patch = new VaultAccountDO();
        patch.setName(name);
        patch.setAccount(account);
        if (password != null) {
            patch.setPasswordEnc(vaultCipherManager.encrypt(password));
        }
        // 仅更新当前分区的非 null 字段：空密码不进 SET，不写分区、添加人或创建时间。
        int rows = vaultAccountMapper.update(patch, partitionQuery(partition).eq(VaultAccountDO::getId, id));
        if (rows == 0) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "该账号已被删除，请刷新后重试");
        }
        log.info("修改密码本条目: id={}, name={}, passwordChanged={}",
                id, patch.getName(), patch.getPasswordEnc() != null);
    }

    /** 两态软删（@TableLogic 转成 UPDATE ... SET deleted = 1），写入同样限定当前分区。 */
    public void delete(DataScope scope, Long id) {
        DataPartition partition = DataPartition.forRequest(scope);
        requireExists(id, partition);
        int rows = vaultAccountMapper.delete(partitionQuery(partition).eq(VaultAccountDO::getId, id));
        if (rows == 0) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "该账号已被删除，请刷新后重试");
        }
        log.info("删除密码本条目: id={}", id);
    }

    /**
     * 显式取回口令。<b>唯一</b>做这件事的方法，返回的是传输层密文，不是明文。
     *
     * <p>密文用 {@link VaultAccountMapper#selectPasswordEncById} 单独取，
     * 不经过实体的 {@code passwordEnc}（那一列被标了 {@code select = false}），
     * 所以不存在"顺手把 DO 返回出去就带上密文"的可能。
     *
     * <p>两次转换的方向与 {@link #create} 相反：存储层解 → 传输层加。明文只在方法栈里过一下，
     * 既不出响应体也不进日志。前端拿到后自己解，见 {@code VaultPasswordModal.tsx}。
     */
    public VaultPasswordVO reveal(DataScope scope, Long id) {
        DataPartition partition = DataPartition.forRequest(scope);
        requireExists(id, partition);
        String cipherText = vaultAccountMapper.selectPasswordEncById(id, partition.scope(), partition.ownerId());
        if (!StringUtils.hasText(cipherText)) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "账号不存在或已删除");
        }
        log.info("查看口令: id={}", id);
        return new VaultPasswordVO(id, transportCipher.encrypt(vaultCipherManager.decrypt(cipherText)));
    }

    /** 传输层解密 + 明文长度校验（长度规则从 DTO 挪来，理由见 {@code VaultAccountCreateRequest#password}）。 */
    private String decryptAndCheckLength(String cipherText) {
        String password = transportCipher.decrypt(cipherText);
        if (password.length() > MAX_PASSWORD_CHARS) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "密码最长 " + MAX_PASSWORD_CHARS + " 个字符");
        }
        return password;
    }

    /** 当前 scope + ownerId 内平台名与账号联合唯一；软删记录与密码不参与判重。 */
    private void requireAccountUnused(DataPartition partition, String name, String account, Long excludeId) {
        if (name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入平台名称");
        }
        if (account.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入账号");
        }
        LambdaQueryWrapper<VaultAccountDO> wrapper = partitionQuery(partition)
                .apply("TRIM(name) = {0}", name)
                .apply("TRIM(account) = {0}", account)
                .ne(excludeId != null, VaultAccountDO::getId, excludeId);
        if (vaultAccountMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.VAULT_ACCOUNT_DUPLICATED, "该平台下已存在相同账号");
        }
    }

    private void requireExists(Long id, DataPartition partition) {
        if (vaultAccountMapper.selectOne(partitionQuery(partition).eq(VaultAccountDO::getId, id)) == null) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "账号不存在");
        }
    }

    private LambdaQueryWrapper<VaultAccountDO> partitionQuery(DataPartition partition) {
        return Wrappers.lambdaQuery(VaultAccountDO.class)
                .eq(VaultAccountDO::getScope, partition.scope())
                .eq(VaultAccountDO::getOwnerId, partition.ownerId());
    }
}
