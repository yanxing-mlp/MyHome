package com.familyhome.vault.biz.controller.b;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.PageResult;
import com.familyhome.common.result.Result;
import com.familyhome.vault.biz.model.request.VaultAccountCreateRequest;
import com.familyhome.vault.biz.model.request.VaultAccountUpdateRequest;
import com.familyhome.vault.biz.model.vo.admin.VaultAccountAdminVO;
import com.familyhome.vault.biz.model.vo.admin.VaultPasswordVO;
import com.familyhome.vault.biz.service.VaultAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密码本 B 端接口。
 *
 * <p>所有入口均要求登录，以查询参数 scope 选择公共或私人分区，默认 PUBLIC。
 * 公共密码由登录账号共享，私人密码仅属于当前账号，ADMIN 也不能跨分区访问；
 * 创建时由服务端确定 ownerId，编辑不能迁移分区。
 *
 * <p>口令只从 {@link #reveal} 这一条 POST 路径返回，响应为传输层密文
 * （见 {@code VaultPasswordVO}）；列表永不返回口令或存储层密文。
 */
@Validated
@RestController
@RequestMapping("/api/b/vault/accounts")
@RequiredArgsConstructor
public class VaultAccountController {

    private final VaultAccountService vaultAccountService;

    @GetMapping
    public Result<PageResult<VaultAccountAdminVO>> page(
            @RequestParam(defaultValue = "PUBLIC") DataScope scope,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(value = 100, message = "每页最多 100 条") int pageSize) {
        return Result.ok(vaultAccountService.page(scope, keyword, pageNo, pageSize));
    }

    @PostMapping
    public Result<Long> create(@RequestParam(defaultValue = "PUBLIC") DataScope scope,
                               @Valid @RequestBody VaultAccountCreateRequest request) {
        return Result.ok(vaultAccountService.create(scope, request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestParam(defaultValue = "PUBLIC") DataScope scope,
                               @Valid @RequestBody VaultAccountUpdateRequest request) {
        vaultAccountService.update(scope, id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "PUBLIC") DataScope scope) {
        vaultAccountService.delete(scope, id);
        return Result.ok();
    }

    /** 查看口令（返回传输层密文，由前端解密展示）。前端只在用户点击"复制/显示"时调用，不做自动预取。 */
    @PostMapping("/{id}/password/reveal")
    public Result<VaultPasswordVO> reveal(@PathVariable Long id,
                                         @RequestParam(defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(vaultAccountService.reveal(scope, id));
    }
}
