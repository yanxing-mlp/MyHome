package com.familyhome.file.api.dto;

import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件 DTO，跨域传递用。
 *
 * <p>通过 {@code FileFacade.mapByIds()} 批量获取，内存组装到相册分组列表、菜谱详情等场景。
 * 刻意不暴露 {@code fileKey} / {@code thumbKey}——对外只给完整 URL（由 facade 拼上 url-prefix）。
 *
 * <p><b>md5 字段的存在理由</b>：业务层查重（同分组内不允许重复图）需要 md5，而 md5 在 file 域。
 * 正确做法是拿 FileDTO 的 md5 在内存比对，而不是跨域 join {@code file_object}（§3.2 约束 2）。
 */
@Getter
@Setter
public class FileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件 ID */
    private Long id;

    /** 真实上传人，供业务域校验新绑定文件的归属 */
    private Long creatorId;

    /** 原图访问 URL，如 /files/2026/09/17/uuid.jpg */
    private String url;

    /** 缩略图访问 URL，生成失败则为 null */
    private String thumbUrl;

    /** 原始文件名 */
    private String originName;

    /** MD5，业务查重用 */
    private String md5;

    private String mimeType;

    private String ext;

    /** 字节 */
    private Long fileSize;

    private Integer width;

    private Integer height;

    /** ALBUM_IMAGE / RECIPE_IMAGE */
    private String bizType;
}
