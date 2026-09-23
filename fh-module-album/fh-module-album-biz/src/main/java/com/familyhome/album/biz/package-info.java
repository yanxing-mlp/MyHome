/**
 * 相册域实现。
 *
 * <p>表前缀 {@code album_}，Flyway 目录 {@code db/migration/album}（版本号段 V2xx）。
 *
 * <p><b>本域两张主表都是三态 {@code status}，{@code @TableLogic} 一处都用不了（方案 §10 风险 5）：</b>
 * <ul>
 *   <li>{@code album_image}：{@code ON_SHELF/OFF_SHELF/DELETED}，"未删除"有两个值，所以
 *       {@code @TableLogic} <b>用不了</b>，每个查询必须手写 {@code status <> 'DELETED'}。漏一处就渲染出裂图，
 *       因为物理文件已经删了。</li>
 *   <li>{@code album_group}：同一套三态（V208 起；原先是两态 {@code deleted} + {@code @TableLogic}，
 *       为了"分组下架"整列换的）。分组多一档作用域：<b>下架 = 整本相册对 C 端消失</b>
 *       （封面卡、详情深链、C 端上传的分组候选三处一起没有它），B 端列表照旧出这行，否则没法再上架回来；
 *       下架<b>不</b>连带下架组内图片，图片各自的 status 不变。</li>
 * </ul>
 * 域外仍是两态的是 {@code file_object} / {@code vault_account}，别照着抄。
 *
 * <p>M3 落地内容：分组 CRUD + 拖拽排序（列表不分页，方案 §5.3）、图片 CRUD + 城市候选、
 * 同分组 MD5 查重（走 {@code FileFacade.mapByIds} 拿 md5 内存比对，<b>禁止 join file_object</b>）、
 * 级联删分组、<b>图片置顶</b>（列 {@code pinned} 已由 V201 建好，见下）。
 *
 * <p><b>图片置顶（v5 需求）的三条口径，M3 直接照做，别再重新设计：</b>
 * <ol>
 *   <li>排序固定 {@code pinned DESC, create_time DESC, id DESC}，写进 {@code AlbumImageService#pageForMp}，
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
