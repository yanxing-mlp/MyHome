package com.familyhome.file.biz.model.vo.admin;

import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 视频视图（B 端"视频管理"列表行）。
 *
 * <p>技术列（{@code fileKey}、{@code md5}）不外露。与文档不同，视频<b>不给静态 {@code url}</b>——
 * 播放走带签名的流式接口（{@link #playUrl}），原因见 {@code VideoService}：{@code <video>} 标签发不出
 * {@code Authorization} 头，只能用短时签名换取可 seek 的 HTTP Range 流。
 */
@Getter
@Setter
public class VideoVO {

    private Long id;

    /** 视频名（上传时的原始文件名，含扩展名） */
    private String name;

    /** 类型列：扩展名大写，如 MP4 / MOV */
    private String fileType;

    private String mimeType;

    /** 字节 */
    private Long fileSize;

    /** 添加人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    /**
     * 播放地址：服务端根相对路径 {@code /api/b/video/{id}/stream?ticket=...}，带一枚短时签名。
     * 前端拼上自己的 base（admin 是 {@code /admin}）后直接塞进 {@code <video src>}。
     */
    private String playUrl;

    private DataScope scope;

    /** PUBLIC 为 0，PRIVATE 为所属账号 */
    private Long ownerId;

    /** 上传时间 */
    private LocalDateTime createTime;
}
