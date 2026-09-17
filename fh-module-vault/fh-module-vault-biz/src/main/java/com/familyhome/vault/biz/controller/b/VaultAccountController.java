package com.familyhome.vault.biz.controller.b;

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
 * 账号本 B 端接口（方案 §5.3）。
 *
 * <p><b>一期不做鉴权，而本模块存的就是口令</b>——这是全项目最需要注意的一处组合，
 * 详见 §5.8 与 §10 风险 1：nginx 内网白名单从"建议配"变成<b>必配</b>，
 * {@code /api/b/vault/**} 绝对不能暴露到公网。
 *
 * <p>明文只从 {@link #reveal} 出去，且用 POST 而不是 GET：GET 的 URL 会进 nginx
 * access log、浏览器历史和任何一层代理日志，口令类动作不该留这种痕迹。
 */
@Validated
@RestController
@RequestMapping("/api/b/vault/accounts")
@RequiredArgsConstructor
public class VaultAccountController {

    private final VaultAccountService vaultAccountService;

    @GetMapping
    public Result<PageResult<VaultAccountAdminVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long pageNo,
            @RequestParam(defaultValue = "20") @Min(1) @Max(value = 100, message = "每页最多 100 条") int pageSize) {
        return Result.ok(vaultAccountService.page(keyword, pageNo, pageSize));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody VaultAccountCreateRequest request) {
        return Result.ok(vaultAccountService.create(request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody VaultAccountUpdateRequest request) {
        vaultAccountService.update(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        vaultAccountService.delete(id);
        return Result.ok();
    }

    /** 查看明文口令。前端只在用户点击"复制/显示"时调用，不做自动预取。 */
    @PostMapping("/{id}/password/reveal")
    public Result<VaultPasswordVO> reveal(@PathVariable Long id) {
        return Result.ok(vaultAccountService.reveal(id));
    }
}
