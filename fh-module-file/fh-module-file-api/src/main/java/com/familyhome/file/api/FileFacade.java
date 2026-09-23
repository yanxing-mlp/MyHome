package com.familyhome.file.api;

import com.familyhome.file.api.dto.FileDTO;
import java.util.List;
import java.util.Map;

/**
 * 文件域门面，跨域调用的唯一入口。
 *
 * <p>设计意图：其他域（album / recipe）只依赖 {@code fh-module-file-api}，不碰任何 service/entity/mapper。
 * 一旦 album 域的代码里出现 {@code @Autowired FileObjectMapper} 或 join {@code file_object}，
 * 两个域就焊死在同一个库里——未来拆微服务时这条线必须剪断。
 *
 * <p><b>调用场景</b>：
 * <ul>
 *   <li>{@link #upload(FileUploadRequest)} — 上传文件，返回 FileDTO（含 url、thumbUrl、md5 等）</li>
 *   <li>{@link #mapByIds(List)} — 批量 ID → URL 映射，列表页渲染缩略图用</li>
 *   <li>{@link #lockByIds(List)} — 在调用方事务内锁文件行，串行校验业务引用</li>
 *   <li>{@link #markDeletedAndPurge(List)} — 先 DB 软删再物理删文件，顺序不能反（§6.7）</li>
 * </ul>
 */
public interface FileFacade {

    /**
     * 上传文件。
     *
     * <p>流程：mime 白名单校验 → 算 MD5 → 秒传判定（命中则硬链接复用物理文件 + 新建 file_object 记录）
     * → 未命中则生成 fileKey 写盘 → Thumbnailator 生成缩略图 → 落 file_object → 返回 FileDTO。
     *
     * @param request 上传请求（含 InputStream、bizType、originName、mimeType、fileSize）
     * @return 文件 DTO，含 url、thumbUrl、width、height、size、md5、duplicated
     */
    FileDTO upload(FileUploadRequest request);

    /**
     * 批量 ID → FileDTO 映射。
     *
     * <p>相册分组列表要显示封面 URL、菜谱详情要展示多张图，都走这个方法拿 Map<Long, FileDTO>，
     * 然后内存组装。**禁止跨域 SQL join** {@code file_object}。
     *
     * @param fileIds 文件 ID 列表
     * @return ID → FileDTO 的 Map；不存在的 ID 不会出现在 Map 里
     */
    Map<Long, FileDTO> mapByIds(List<Long> fileIds);

    /**
     * 按文件 ID 升序锁定文件行（含已软删行），持锁直到调用方事务结束。
     *
     * <p>调用方必须已开启事务；本方法不创建独立事务。批量 ID 应一次传入，
     * 必须先锁文件再写业务图片/关系，等锁后再读取文件及引用，不得沿用等待前的快照。
     * 不存在的 ID 不会被锁定，调用方仍须校验文件存在性与业务权限。
     */
    void lockByIds(List<Long> fileIds);

    /**
     * 标记删除并物理清理。
     *
     * <p><b>执行顺序</b>：事务内 {@code file_object.deleted = 1} → 事务提交成功后
     * {@code storageClient.delete(fileKey)} + {@code delete(thumbKey)}。
     * 反过来（先删文件再提交 DB），事务一回滚就留下"记录指向不存在文件"的永久 404。
     *
     * <p>删文件失败只记 ERROR 日志，不回滚、不抛给调用方——最坏情况是磁盘浪费，可被清理任务兜住。
     *
     * @param fileIds 要删除的文件 ID 列表
     */
    void markDeletedAndPurge(List<Long> fileIds);
}
