package com.familyhome.vault.biz.controller.c;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.vault.biz.converter.VaultAccountConverter;
import com.familyhome.vault.biz.model.vo.admin.VaultPasswordVO;
import com.familyhome.vault.biz.model.vo.client.VaultAccountClientVO;
import com.familyhome.vault.biz.service.VaultAccountService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密码本域 C 端接口：<b>纯只读</b>——看列表 + 显式取回口令，但<b>不新增、不编辑、不删除</b>
 * （增删改仍只在 B 端"密码本"，与相册/视频 C 端"能看不能删"、且 C 端从不做管理动作同一口径）。
 *
 * <p>与视频/相册 C 端同一手法——路径与返回形状按 C 端裁剪，查询/分区/口令加解密仍与 B 端共用同一个
 * {@link VaultAccountService}（列表调 {@link VaultAccountService#list}，reveal 直接调
 * {@link VaultAccountService#reveal}），不是转发层、也没有第二份实现。
 *
 * <p>分区口径与 B 端一致：{@code scope} 默认 PUBLIC（家庭密码本，全家共享），PRIVATE 只落在当前账号自己的
 * 私人密码本（属主由服务端按登录令牌判定，ADMIN 无例外，跨属主裸 id 一律 404）。两条接口都要求登录。
 *
 * <p>口令只从 {@link #reveal} 这一条 POST 路径返回，响应为传输层密文（见 {@code VaultPasswordVO}），
 * 由前端 {@code @family-home/shared/crypto} 解密后展示；列表永不返回口令或存储层密文。用 POST 而非 GET：
 * GET 的 URL（含 id）会进 nginx access log 和浏览器历史，口令类动作不留这种痕迹。
 */
@Validated
@RestController
@RequestMapping("/api/c/vault/accounts")
@RequiredArgsConstructor
public class VaultCController {

    private final VaultAccountService vaultAccountService;

    /** 只读列表：当前分区全部条目，最近改动在前，整表返回不分页；裁成 C 端视图（不含分区/属主/添加人/时间）。 */
    @GetMapping
    public Result<List<VaultAccountClientVO>> list(
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        List<VaultAccountClientVO> list = vaultAccountService.list(scope).stream()
                .map(VaultAccountConverter::toClientVO)
                .toList();
        return Result.ok(list);
    }

    /** 查看口令（返回传输层密文，由前端解密展示）。前端只在用户点击"显示/复制"时调用，不做自动预取。 */
    @PostMapping("/{id}/password/reveal")
    public Result<VaultPasswordVO> reveal(
            @PathVariable Long id,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(vaultAccountService.reveal(scope, id));
    }
}
