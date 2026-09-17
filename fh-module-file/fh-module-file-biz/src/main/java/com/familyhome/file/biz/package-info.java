/**
 * 文件域实现。
 *
 * <p>表前缀 {@code file_}，Flyway 目录 {@code db/migration/file}（版本号段 V1xx）。
 *
 * <p>M2 落地内容：
 * <ul>
 *   <li>{@code StorageClient} 接口 + {@code LocalStorageClient} 唯一实现（含 {@code link()} 硬链接，
 *       跨文件系统时 fallback 到复制）。<b>不做 {@code fh.storage.type} 配置开关</b>——
 *       只有一个实现时那是死代码，未来接 OSS 时再加 {@code @ConditionalOnProperty}。</li>
 *   <li>MD5 秒传：命中则新建 file_object 记录 + 硬链接复用物理文件，<b>不共享记录</b>，
 *       以保持"一条 file_object 对应一个业务引用"的删除语义（方案 §6.9）。</li>
 *   <li>缩略图：长边 480、质量 0.8。前端 canvas 转码已应用 EXIF 旋转，<b>后端不需要再旋转</b>（方案 §6.6）。</li>
 *   <li>EXIF fallback：前端没传 lng/lat 且文件自带 EXIF 时补读一次。</li>
 * </ul>
 *
 * <p>本包不感知任何业务域——{@code bizType} 只是统计标签，file 域不知道相册和菜谱的存在。
 */
package com.familyhome.file.biz;
