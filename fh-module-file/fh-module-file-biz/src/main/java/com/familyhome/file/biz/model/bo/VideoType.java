package com.familyhome.file.biz.model.bo;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

/**
 * 视频文件类型 = 扩展名 + mime，由服务端在上传时按<b>文件名</b>解析（浏览器给的 Content-Type 一律不信），
 * 与 {@link DocumentType} 同一手法。
 *
 * <p><b>与文档的唯一差异：视频要挡格式</b>。文档"什么扩展名都收"（用户明确要求），因为它只是存下来给人下载；
 * 视频要能被播放器解码，收一个 {@code .exe} 进"视频管理"只会得到一屏黑框和一句解码失败。所以这里保留一道
 * 白名单：解析出的 mime 以 {@code video/} 开头，或扩展名在 {@link #VIDEO_EXTENSIONS} 里，才放行；否则
 * 抛 {@code FILE_TYPE_UNSUPPORTED}（前端 {@code accept="video/*"} 先挡一道，这里是服务端兜底）。
 *
 * <p>扩展名清洗规则与 {@link DocumentType} 完全一致（只留 {@code [a-z0-9]}、最长 16 字符）——它会被拼进
 * 落盘的 {@code fileKey}，不洗就是路径注入的入口。
 */
public record VideoType(String ext, String mimeType) {

    private static final int MAX_EXT_LENGTH = 16;

    /**
     * 常见视频容器。Spring 的 mime.types 认识 mp4/webm/ogv/3gp/mpeg/mov(quicktime)/avi(x-msvideo)，
     * 但 m4v / mkv / ts 这几支要么没有、要么映射不稳，所以按扩展名再兜一层。
     */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "mov", "webm", "mkv", "avi", "ogv", "3gp", "ts", "mpg", "mpeg", "flv", "wmv");

    private static final String FALLBACK_MIME = "application/octet-stream";

    /**
     * 解析并校验。非视频抛 {@link com.familyhome.common.exception.ErrorCode#FILE_TYPE_UNSUPPORTED}。
     */
    public static VideoType resolve(String originName) {
        String ext = sanitizeExtension(originName);
        String mime = MediaTypeFactory.getMediaType("file." + ext)
                .map(MediaType::toString)
                .orElse(FALLBACK_MIME);
        boolean ok = mime.startsWith("video/") || VIDEO_EXTENSIONS.contains(ext);
        if (!ok) {
            throw com.familyhome.common.exception.BizException.of(
                    com.familyhome.common.exception.ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "只支持上传视频文件（mp4 / mov / webm / mkv / avi 等）");
        }
        // 表里查不到但扩展名在白名单（mkv/m4v/ts）时，mime 落 octet-stream 会让 <video> 拒绝解码，
        // 按扩展名补一个能用的默认 mime。
        if (FALLBACK_MIME.equals(mime)) {
            mime = defaultMimeForExt(ext);
        }
        return new VideoType(ext, mime);
    }

    private static String defaultMimeForExt(String ext) {
        return switch (ext) {
            case "mkv" -> "video/x-matroska";
            case "m4v" -> "video/mp4";
            case "ts" -> "video/mp2t";
            case "flv" -> "video/x-flv";
            case "wmv" -> "video/x-ms-wmv";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            default -> "video/mp4";
        };
    }

    private static String sanitizeExtension(String originName) {
        if (originName == null) {
            return "";
        }
        int dot = originName.lastIndexOf('.');
        if (dot < 0 || dot == originName.length() - 1) {
            return "";
        }
        String raw = originName.substring(dot + 1).toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(Math.min(raw.length(), MAX_EXT_LENGTH));
        for (int i = 0; i < raw.length() && safe.length() < MAX_EXT_LENGTH; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                safe.append(c);
            }
        }
        return safe.toString();
    }
}
