package com.familyhome.file.biz.controller.c;

import com.familyhome.common.result.Result;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.FileUploadRequest;
import com.familyhome.file.api.dto.FileDTO;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件域 C 端接口（方案 §5.4）。
 *
 * <p>只有一条上传，给 C 端相册页那个上传浮层用。与 B 端 {@code POST /api/b/file/upload} 同一口径：
 * 两个 part（{@code file} + {@code bizType}），落库都走同一个 {@link FileFacade}，
 * 所以 md5 秒传、缩略图、{@code file_object} 那套只有一份实现。
 *
 * <p>这里<b>不收 EXIF</b>（{@code file_object} 没有 {@code lng}/{@code lat}/{@code shootTime} 那三列）：
 * 前端 exifr 提取出来的位置与拍摄时间要随相册的绑定接口进 {@code album_image}，
 * 见 {@code POST /api/c/album/groups/{groupId}/images}。
 *
 * <p>B 端独有的文件管理（分类字典、文档列表/删除、存储路径信息）在这里一概没有。
 */
@Validated
@RestController
@RequestMapping("/api/c/file")
@RequiredArgsConstructor
public class FileCController {

    private final FileFacade fileFacade;

    /**
     * 单文件上传，返回 {@link FileDTO}（含 id、url、thumbUrl、width、height、size、md5、duplicated）。
     *
     * <p>C 端只用 {@code id}（拿它去绑定相册分组），其余字段留在响应里不另裁。
     */
    @PostMapping("/upload")
    public Result<FileDTO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("bizType") @NotBlank String bizType) throws IOException {

        FileUploadRequest request = new FileUploadRequest();
        request.setInputStream(file.getInputStream());
        request.setBizType(bizType);
        request.setOriginName(file.getOriginalFilename());
        request.setMimeType(file.getContentType());
        request.setFileSize(file.getSize());

        return Result.ok(fileFacade.upload(request));
    }
}
