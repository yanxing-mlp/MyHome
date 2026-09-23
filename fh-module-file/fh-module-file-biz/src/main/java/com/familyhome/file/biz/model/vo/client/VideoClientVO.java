package com.familyhome.file.biz.model.vo.client;

import lombok.Getter;
import lombok.Setter;

/**
 * 视频视图（C 端"看视频"列表行）。
 *
 * <p>是 B 端 {@link com.familyhome.file.biz.model.vo.admin.VideoVO} 的裁剪版：C 端只"看"，
 * 不展示添加人，也不下发分区 / 属主 / mime 这些内页用不到的内部字段——与相册 C 端只回
 * {@code AlbumImageBriefVO} 那几列同一口径。裁剪在 {@code VideoCController} 里做，服务层仍返回 {@code VideoVO}。
 *
 * <p>{@link #playUrl} 指向 <b>C 端自有</b>的 {@code /api/c/video/{id}/stream}（不是 B 端路径），
 * 带的是同一枚短时签名票据；h5 的 base 是 {@code /}，可直接塞进 {@code <video src>}。
 */
@Getter
@Setter
public class VideoClientVO {

    private Long id;

    /** 视频名（上传时的原始文件名，含扩展名） */
    private String name;

    /** 类型列：扩展名大写，如 MP4 / WEBM */
    private String fileType;

    /** 字节，前端用 shared 的 formatFileSize 展示 */
    private Long fileSize;

    /**
     * 播放地址：服务端根相对路径 {@code /api/c/video/{id}/stream?ticket=...}，带一枚短时签名票据。
     * 不给静态 url 的理由与 B 端一致——{@code <video>} 发不出 {@code Authorization} 头，只能用签名换可 seek 的流。
     */
    private String playUrl;
}
