/**
 * 相册域实现。
 *
 * <p>表前缀 {@code album_}，Flyway 目录 {@code db/migration/album}（版本号段 V2xx）。
 *
 * <p><b>两种删除机制并存，写代码时容易搞混（方案 §10 风险 5）：</b>
 * <ul>
 *   <li>{@code album_group} 是两态 {@code deleted TINYINT}，<b>可以</b>挂 MyBatis-Plus {@code @TableLogic} 自动过滤。</li>
 *   <li>{@code album_image} 是三态 {@code status}（ON_SHELF/OFF_SHELF/DELETED），{@code @TableLogic} <b>用不了</b>
 *       （"未删除"有两个值），每个查询必须手写 {@code status <> 'DELETED'}。漏一处就渲染出裂图，
 *       因为物理文件已经删了。</li>
 * </ul>
 *
 * <p>M3 落地内容：分组 CRUD + 拖拽排序（列表不分页，方案 §5.3）、图片 CRUD + 城市候选、
 * 同分组 MD5 查重（走 {@code FileFacade.mapByIds} 拿 md5 内存比对，<b>禁止 join file_object</b>）、
 * 级联删分组、<b>图片置顶</b>（列 {@code pinned} 已由 V201 建好，见下）。
 *
 * <p><b>图片置顶（v5 需求）的三条口径，M3 直接照做，别再重新设计：</b>
 * <ol>
 *   <li>排序固定 {@code pinned DESC, create_time DESC, id DESC}，写进 {@code AlbumImageService#baseQuery()}，
 *       B/C 两端共用同一份——置顶的意义就是"打开分组第一眼看到"。</li>
 *   <li>置顶<b>不碰</b> {@code update_time}：该列有 {@code ON UPDATE CURRENT_TIMESTAMP}，
 *       所以 pin/unpin 的 UPDATE 必须显式带上 {@code update_time = update_time}，
 *       否则"我置顶了一下，修改时间变了"会让列表顺序自己跳。</li>
 *   <li>置顶只在分组内生效，{@code pinned} 不参与跨分组查询，也<b>不</b>为它加索引：
 *       图片查询永远带 {@code status <> 'DELETED'} 这个范围条件，加了索引也用不上。</li>
 * </ol>
 *
 * <p>跨域依赖：只允许 {@code fh-module-file-api}。
 */
package com.familyhome.album.biz;
