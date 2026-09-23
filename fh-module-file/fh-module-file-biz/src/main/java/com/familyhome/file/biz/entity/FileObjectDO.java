package com.familyhome.file.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.familyhome.common.enums.DataScope;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件对象记录，表 {@code file_object}。
 *
 * <p>一条记录对应一个物理文件（或硬链接），{@code biz_type} 标记归属域。
 * 秒传时新建新记录 + 硬链接复用物理文件，而不是共享同一条记录——这样删除语义简单：
 * unlink 自己的那份，其他引用不受影响（§6.9）。
 */
@Getter
@Setter
@TableName("file_object")
public class FileObjectDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 相对存储根的路径，如 2026/09/17/uuid.jpg */
    private String fileKey;

    /** 缩略图相对路径，生成失败则为空 */
    private String thumbKey;

    /** 上传时原始文件名 */
    private String originName;

    /** 文件内容 MD5，秒传去重用 */
    private String md5;

    private String mimeType;

    private String ext;

    /** 字节 */
    private Long fileSize;

    private Integer width;

    private Integer height;

    /** 1=物理文件是硬链接（秒传产生），排查用 */
    private Integer hardLink;

    /**
     * 归属域标记：ALBUM_IMAGE / RECIPE_IMAGE / document。
     *
     * <p>图片两个取值由前端上传时传进来（B/C 端一致），文档那个由 {@code DocumentFileService} 自己写死，
     * 因为大小写不一致是历史遗留：早期 B 端相册/菜谱传的是小写 'album' / 'recipe'，那些行还留在库里。
     * file 域不按它做分支，只有文件管理页按 {@code = document} 筛，所以旧值不影响功能。
     */
    private String bizType;

    /**
     * 添加人（{@code app_user.id}），V104 加列。
     *
     * <p>图片行的添加人在 {@code FileFacadeImpl#upload} 里写，文档行在
     * {@code DocumentFileService#upload} 里写，两边都取 {@code CurrentUserHolder.requireUserId()}，
     * 所以"没登录就上传"是 401 而不是"落一条 null"。历史存量由 V501 统一洗成大宝。
     */
    private Long creatorId;

    /** 文件分类，仅 {@code bizType = document} 使用；图片行是 null */
    private Long categoryId;

    /** 仅用于文档分区；所有图片固定 PUBLIC/0，不代表相册的可见权限 */
    private DataScope scope;

    /** 公共文档及图片为 0；私人文档为所属账号，不能由请求体指定 */
    private Long ownerId;

    /** 两态删除：1=物理文件已删除，记录仅留审计 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
