/**
 * 相册域对外契约。
 *
 * <p>只允许放 Facade 接口与跨域 DTO，禁止实现/entity/mapper。
 *
 * <p>一期无跨域调用方（C 端 home 聚合接口已推迟到二期），接口先建着备用。
 * 二期需要暴露 {@code getHomeSummary()} 给 fh-boot 聚合，见方案 §5.5 存档。
 */
package com.familyhome.album.api;
