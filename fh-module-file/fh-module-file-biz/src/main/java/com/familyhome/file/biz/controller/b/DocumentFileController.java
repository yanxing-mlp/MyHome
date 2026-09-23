package com.familyhome.file.biz.controller.b;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.DocumentFileVO;
import com.familyhome.file.biz.service.DocumentFileService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档文件 B 端接口（方案 §5.3），供"文件管理"页使用。
 *
 * <p>与图片上传接口（{@code POST /api/b/file/upload}）分开：那条走 FileFacade 的
 * mime 白名单 + 缩略图 + EXIF，是给相册/菜谱用的；这里<b>不限文件格式</b>（2026-09-21 起，
 * 原先只收 csv/md/doc/docx），带分类。图片那一侧的白名单没动——它要解码、生成缩略图，收下来不是图就整条路都错。
 *
 * <p>scope 查询参数默认为 PUBLIC。私人文件只通过带身份的下载接口返回，不提供静态 URL。
 */
@Validated
@RestController
@RequestMapping("/api/b/file/documents")
@RequiredArgsConstructor
public class DocumentFileController {

    private final DocumentFileService documentFileService;

    /** 列表，最近上传在前；categoryId 不传 = 全部 */
    @GetMapping
    public Result<List<DocumentFileVO>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(documentFileService.list(categoryId, scope));
    }

    /**
     * 上传一份文档。
     *
     * <p>multipart/form-data：{@code file} 文件本体，{@code categoryId} 分类（必选）。
     * 类型（扩展名 + mime）由服务端按文件名解析，前端不用传、也不能传。
     *
     * <p>categoryId 用 {@code @RequestParam} 而不是 {@code @RequestPart}：后者对非字符串
     * 参数会走 HttpMessageConverter 读 part，而表单 part 没有 Content-Type（默认
     * application/octet-stream），没有转换器接手 → 直接 500。文件本体仍用 {@code @RequestPart}，
     * 与图片上传接口保持一致。
     */
    @PostMapping
    public Result<DocumentFileVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("categoryId") Long categoryId,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(documentFileService.upload(file, categoryId, scope));
    }

    /** 删除（软删记录 + 物理删文件） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        documentFileService.delete(id, scope);
        return Result.ok();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return documentFileService.download(id, scope);
    }
}
