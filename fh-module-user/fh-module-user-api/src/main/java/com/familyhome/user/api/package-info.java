/**
 * 账号域对外契约（一期为空）。
 *
 * <p>为什么是空的：各业务域列表要显示"添加人"，前端拿 {@code creatorId} 去账号字典
 * （B 端 {@code GET /api/b/user/options}、C 端 {@code GET /api/c/user/options}）里查昵称，
 * 后端<b>不</b>跨域解析名字——这与做法选项
 * "名字不进接口、由 admin 现查做法字典"是同一口径（方案 §7.3 实现实况）。
 * 少这一条依赖，album / recipe / file / vault 四个域就都不用引 user-api，域边界保持四条边不变。
 *
 * <p>将来真要后端解析（导出报表、按人统计等）时在这里加 {@code UserFacade}，
 * 语义对齐 {@code FileFacade.mapByIds}：一次批量、返回 {@code Map<Long, ...>}。
 */
package com.familyhome.user.api;
