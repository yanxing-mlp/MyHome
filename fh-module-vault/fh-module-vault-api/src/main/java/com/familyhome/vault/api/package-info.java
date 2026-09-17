/**
 * 账号本域对外契约。
 *
 * <p>只允许放 Facade 接口与跨域 DTO，禁止实现/entity/mapper。
 *
 * <p><b>本域一期和可预见的二期都不应该有跨域调用方</b>：相册、菜谱、文件都没有读口令的理由，
 * 一旦出现 {@code album-biz -> vault-api} 这种依赖，边界就失守了。所以这里只留包声明。
 *
 * <p>将来若真需要跨域（例如二期做"账号本导入导出"独立服务、或 C 端只读的"我的账号"页），
 * 也只在 {@code fh-boot} 里聚合调用，不要让内容域互相依赖。
 */
package com.familyhome.vault.api;
