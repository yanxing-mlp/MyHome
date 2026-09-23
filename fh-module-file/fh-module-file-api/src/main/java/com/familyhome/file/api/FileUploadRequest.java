package com.familyhome.file.api;

import java.io.InputStream;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件上传请求。
 *
 * <p>注意 {@code inputStream} 只能读一次——调用方负责在上传完成后关闭它。
 *
 * <p>这里<b>没有 EXIF 三项</b>（lng/lat/shootTime）：{@code file_object} 不存这些列，
 * 文件域存不了也就没必要收。相册要落 EXIF 走绑定项
 * （{@code AlbumImageBindRequest}），由 {@code album_image} 那一行持有。
 */
@Getter
@Setter
public class FileUploadRequest {

    /** 文件流 */
    private InputStream inputStream;

    /** album / recipe（文档上传不走这个门面，走 DocumentFileService，见 biz 模块） */
    private String bizType;

    /** 原始文件名 */
    private String originName;

    /** MIME 类型，如 image/jpeg */
    private String mimeType;

    /** 文件大小（字节）*/
    private Long fileSize;
}
