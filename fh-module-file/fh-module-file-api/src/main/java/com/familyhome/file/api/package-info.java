/**
 * 文件域对外契约。
 *
 * <p>这是其他业务域访问文件能力的<b>唯一入口</b>。本包及其子包只允许出现：
 * <ul>
 *   <li>Facade 接口（一期为同进程 {@code @Service} 实现，未来拆系统时换成 Dubbo/Feign 客户端，调用方零改动）</li>
 *   <li>跨域传输 DTO</li>
 * </ul>
 *
 * <p><b>禁止</b>放实现类、entity、mapper。禁止其他域依赖 {@code fh-module-file-biz}。
 *
 * <p>核心方法（M2 实现）：
 * <ul>
 *   <li>{@code upload} —— 写盘 + MD5 秒传（硬链接）+ 缩略图</li>
 *   <li>{@code mapByIds} —— 批量换 URL，FileDTO 带 md5 供 album 域做同分组查重（方案 §6.9）</li>
 *   <li>{@code markDeletedAndPurge} —— 先 DB 事务标记、提交后物理删文件，顺序不能反（方案 §6.7）</li>
 * </ul>
 */
package com.familyhome.file.api;
