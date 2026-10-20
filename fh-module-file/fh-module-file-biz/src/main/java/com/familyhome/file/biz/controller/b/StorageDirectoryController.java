package com.familyhome.file.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.file.biz.storage.StorageDirectoryRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 只允许登记新目录与切换后续上传的默认目录，不提供修改路径或删除接口。 */
@RestController
@RequestMapping("/api/b/storage")
public class StorageDirectoryController {

    private final StorageDirectoryRegistry registry;

    public StorageDirectoryController(StorageDirectoryRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Result<StorageDirectoryRegistry.Snapshot> list() {
        return Result.ok(registry.listForAdmin());
    }

    @GetMapping("/folders")
    public Result<StorageDirectoryRegistry.FolderListing> folders(
            @RequestParam(name = "path", required = false) String path) {
        return Result.ok(registry.browseForAdmin(path));
    }

    @PostMapping
    public Result<Long> add(@RequestBody @Valid AddRequest request) {
        return Result.ok(registry.addForAdmin(request.path()));
    }

    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable("id") long id) {
        registry.setDefaultForAdmin(id);
        return Result.ok();
    }

    public record AddRequest(@NotBlank @Size(max = 1024) String path) {
    }
}
