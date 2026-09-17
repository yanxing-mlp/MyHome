package com.familyhome.vault.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.util.StringUtils;

/**
 * 账号本业务。
 *
 * <p>{@code VaultAccountDO} 上的 {@code @TableLogic} 会自动给所有 BaseMapper 查询加
 * {@code deleted = 0}，所以这里<b>不需要</b>像 album/recipe 那样手写过滤条件
 * （两种删除机制的区别见方案 §10 风险 5）。
 *
 * <p>口令只在 {@link #reveal} 这一条路径上出现明文；{@code update} 走
 * "password 为空即不改"的语义，靠 MyBatis-Plus 默认的 NOT_NULL 更新策略实现
 * ——{@code patch.passwordEnc == null} 时该列根本不会进 SET 子句。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultAccountService {

    private final VaultAccountMapper vaultAccountMapper;
    private final VaultCipherManager vaultCipherManager;

    /**
     * 分页列表。<b>默认按更新时间倒序</b>（最近动过的排前面），不做"名称拼音排序"——
     * 那需要 GBK 字符集或拼音列，家庭场景下"最近改动"更有用。
     */
    public PageResult<VaultAccountAdminVO> page(String keyword, long pageNo, int pageSize) {
        LambdaQueryWrapper<VaultAccountDO> query = Wrappers.lambdaQuery(VaultAccountDO.class);
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

    public Long create(VaultAccountCreateRequest request) {
        VaultAccountDO entity = new VaultAccountDO();
        entity.setName(request.getName().trim());
        entity.setAccount(request.getAccount().trim());
        entity.setPasswordEnc(vaultCipherManager.encrypt(request.getPassword()));
        vaultAccountMapper.insert(entity);
        // 只记 id 和 name，绝不记请求对象本身（toString 含明文口令）
        log.info("新增账号本条目: id={}, name={}", entity.getId(), entity.getName());
        return entity.getId();
    }

    public void update(Long id, VaultAccountUpdateRequest request) {
        requireExists(id);

        VaultAccountDO patch = new VaultAccountDO();
        patch.setId(id);
        patch.setName(request.getName().trim());
        patch.setAccount(request.getAccount().trim());
        if (StringUtils.hasText(request.getPassword())) {
            patch.setPasswordEnc(vaultCipherManager.encrypt(request.getPassword()));
        }
        // updateById 只写非 null 字段：password 为空时 password_enc 不进 SET，旧密文原样保留
        int rows = vaultAccountMapper.updateById(patch);
        if (rows == 0) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "该账号已被删除，请刷新后重试");
        }
        log.info("修改账号本条目: id={}, name={}, passwordChanged={}",
                id, patch.getName(), patch.getPasswordEnc() != null);
    }

    /** 两态软删（@TableLogic 的 deleteById 就是 UPDATE ... SET deleted = 1）。 */
    public void delete(Long id) {
        requireExists(id);
        int rows = vaultAccountMapper.deleteById(id);
        if (rows == 0) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "该账号已被删除，请刷新后重试");
        }
        log.info("删除账号本条目: id={}", id);
    }

    /**
     * 显式取明文口令。<b>唯一</b>返回明文的方法。
     *
     * <p>密文用 {@link VaultAccountMapper#selectPasswordEncById} 单独取，
     * 不经过实体的 {@code passwordEnc}（那一列被标了 {@code select = false}），
     * 所以不存在"顺手把 DO 返回出去就带上密文"的可能。
     */
    public VaultPasswordVO reveal(Long id) {
        String cipherText = vaultAccountMapper.selectPasswordEncById(id);
        if (!StringUtils.hasText(cipherText)) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "账号不存在或已删除");
        }
        log.info("查看口令: id={}", id);
        return new VaultPasswordVO(id, vaultCipherManager.decrypt(cipherText));
    }

    private void requireExists(Long id) {
        if (vaultAccountMapper.selectById(id) == null) {
            throw BizException.notFound(ErrorCode.VAULT_ACCOUNT_NOT_FOUND, "账号不存在");
        }
    }
}
