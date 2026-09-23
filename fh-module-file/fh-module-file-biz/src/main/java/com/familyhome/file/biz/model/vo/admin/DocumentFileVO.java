package com.familyhome.file.biz.model.vo.admin;

import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档文件视图（B 端"文件管理"列表行）。
 *
 * <p>只给展示需要的字段：物理路径 {@code fileKey}、md5 这些技术列一律不外露，
 * 仅公共文件的 {@code url} 是静态资源地址，私人文件必须经鉴权下载。
 */
@Getter
@Setter
public class DocumentFileVO {

    private Long id;

    /** 文件名（上传时的原始名，含扩展名） */
    private String name;

    private Long categoryId;

    /** 分类名；分类被删时为空，前端按"未分类"兜底展示 */
    private String categoryName;

    /** 文件类型：CSV / MD / DOC / DOCX，上传时由服务端解析，不是前端传的 */
    private String fileType;

    /** 字节 */
    private Long fileSize;

    /** 添加人（{@code app_user.id}），昵称由前端从 {@code /api/b/user/options} 字典解析 */
    private Long creatorId;

    /** 公共文件的静态地址；私人文件为 null，必须经鉴权下载接口读取 */
    private String url;

    private DataScope scope;

    /** PUBLIC 为 0，PRIVATE 为当前账号 */
    private Long ownerId;

    /** 上传时间 */
    private LocalDateTime createTime;
}
