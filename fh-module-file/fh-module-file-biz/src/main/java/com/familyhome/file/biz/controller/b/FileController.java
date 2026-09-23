package com.familyhome.file.biz.controller.b;

import com.familyhome.common.result.Result;
import com.familyhome.file.api.FileFacade;
import com.familyhome.file.api.FileUploadRequest;
import com.familyhome.file.api.dto.FileDTO;
import com.familyhome.file.biz.config.FileStorageConfig;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件域 B 端接口（方案 §5.3）。
 *
 * <p>目前只有一个上传接口，未来可能有批量上传、分片上传等扩展。
 */
@Validated
@RestController
@RequestMapping("/api/b/file")
@RequiredArgsConstructor
public class FileController {

    private final FileFacade fileFacade;
    private final FileStorageConfig storageConfig;

    /**
     * 单文件上传。
     *
     * <p>前端传 multipart/form-data，只有两个 part：
     * <ul>
     *   <li>{@code file} — 文件本身（已由前端 canvas 转成 JPEG，长边 ≤2560）</li>
     *   <li>{@code bizType} — ALBUM_IMAGE / RECIPE_IMAGE</li>
     * </ul>
     *
     * <p>这里<b>不收 EXIF</b>（原先声明过 {@code lng}/{@code lat}/{@code shootTime} 三个 part，
     * 但文件域一层一处都不读 —— {@code file_object} 没这几列，传了等于没传，纯属空转）。
     * EXIF 要落库就走相册的绑定接口（C 端 {@code POST /api/c/album/groups/{groupId}/images}、
     * B 端 {@code POST /api/b/album/groups/{groupId}/images}），
     * 绑定时写在 {@code album_image} 那一行上（方案 §6.6 坑 1：canvas 转码会把 EXIF 全丢掉，
     * 所以提取必须发生在前端、且只能随绑定项进库）。
     *
     * <p>返回 FileDTO，含 url、thumbUrl、width、height、size、md5、duplicated。
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

        FileDTO dto = fileFacade.upload(request);
        return Result.ok(dto);
    }

    /**
     * 获取存储路径信息。
     *
     * <p>返回相册根目录、分组目录结构说明等元数据。
     */
    @GetMapping("/storage-info")
    public Result<Map<String, String>> getStorageInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("rootDirectory", storageConfig.getRoot());
        info.put("urlPrefix", storageConfig.getUrlPrefix());
        info.put("structure", "root/yyyy/MM/dd/{uuid}.jpg");
        return Result.ok(info);
    }
}
