package com.familyhome.file.biz.model.bo;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

/**
 * 文档文件类型 = 扩展名 + mime，由服务端在上传时按<b>文件名</b>解析（浏览器给的 Content-Type 一律不信）。
 *
 * <p>2026-09-21 起<b>不限格式</b>（用户："文件管理，我希望什么格式的文件都能上传"）。原先这是一个只收
 * csv / md / doc / docx 的枚举，还带第二步"真实字节对不对得上这个扩展名"的嗅探（把 xlsx 改名成 .csv 会被拒）。
 * 白名单和嗅探一起删了：既然什么扩展名都收，"内容与扩展名不符"就不是一回事了——列表里"类型"那一列
 * 本来就只是扩展名大写，跟真实格式无关，拦得再严也保证不了能打开。
 *
 * <p>只剩两件事必须做：
 * <ul>
 *   <li><b>洗扩展名</b>：只留 {@code [a-z0-9]}、最长 16 字符。它会被拼进 {@code fileKey} 当作盘文件名的后缀，
 *       不洗干净就是路径注入的入口（文件名 {@code a.b/c} 的"扩展名"是 {@code b/c}）。</li>
 *   <li><b>mime 查表</b>：Spring 自带的 mime.types 有几百条（{@link MediaTypeFactory}），
 *       覆盖了 pdf / zip / xlsx / mp4 这些常见格式，查不到就落 {@code application/octet-stream}。
 *       只有 md 那张表里没有，单独补一条以保持原有口径。</li>
 * </ul>
 *
 * <p>没有扩展名的文件照样收：{@link #ext()} 是空串，落盘的 key 也就不带后缀，页面"类型"那一列留空。
 * 单文件大小上限仍是 {@code spring.servlet.multipart} 那一档（超限由全局异常处理给出中文提示），
 * 这次没动。
 */
public record DocumentType(String ext, String mimeType) {

    /** 落盘文件名的后缀长度上限；列宽足够，超长的基本是伪造文件名 */
    private static final int MAX_EXT_LENGTH = 16;

    /** Spring 的 mime.types 里缺的两支文本格式，而它们正是这一页最常见的一类 */
    private static final Map<String, String> NOT_IN_SPRING_TABLE = Map.of(
            "md", "text/markdown",
            "markdown", "text/markdown");

    /** 查不到就落这个：只影响元数据，静态映射按真实文件名的后缀给响应头，不靠这一列 */
    private static final String FALLBACK_MIME = "application/octet-stream";

    public static DocumentType resolve(String originName) {
        String ext = sanitizeExtension(originName);
        return new DocumentType(ext, mimeTypeOf(ext));
    }

    private static String mimeTypeOf(String ext) {
        String known = NOT_IN_SPRING_TABLE.get(ext);
        if (known != null) {
            return known;
        }
        // 拿"file.<洗过的扩展名>"去查，而不是原始文件名：表键是小写，原始扩展名常是大写（REPORT.PDF）
        return MediaTypeFactory.getMediaType("file." + ext)
                .map(MediaType::toString)
                .orElse(FALLBACK_MIME);
    }

    /**
     * 取最后一个点之后的部分并转小写，只留字母和数字；没有点（或点结尾）返回空串。
     */
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
